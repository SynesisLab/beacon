package dev.beacon.core.chat

import dev.beacon.core.api.ChatsApi
import dev.beacon.core.api.InstanceSource
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.dto.ChatPayload
import dev.beacon.core.data.local.MessageEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * §13: MockWebServer replays the fixtures against the parser + ENGINE (not just
 * the parser): fixture bytes → real OkHttp → engine fold → ChatUiModel golden,
 * with an in-memory [ChatStore].
 */
class ChatEngineTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private lateinit var server: MockWebServer

    /**
     * Path-based dispatch (mockwebserver3): streams route to /api/chat/completions,
     * everything else (the completed round-trip, tasks/stop) gets a canned OK —
     * immune to the stream/completed arrival races that made FIFO enqueue flaky.
     */
    private val pendingStreams = ArrayDeque<Pair<String, String>>() // content-type, body
    private val dispatcher = object : mockwebserver3.Dispatcher() {
        override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
            if (request.url.encodedPath == "/api/chat/completions") {
                val next = synchronized(pendingStreams) { pendingStreams.removeFirstOrNull() }
                    ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(200).addHeader("Content-Type", next.first).body(next.second).build()
            }
            return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build()
        }
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun newEngine(): Pair<ChatEngine, InMemoryChatStore> {
        val root = server.url("/").toString().trimEnd('/')
        val client = OkHttpClient()
        val api = OwuiApi(client, json, InstanceSource { root })
        val chatsApi = ChatsApi(client, json, InstanceSource { root })
        val store = InMemoryChatStore()
        return ChatEngine(api, store, json, appContext = null) to store
    }

    private suspend fun awaitIdle(engine: ChatEngine): ChatEngine.ChatUiModel =
        withTimeout(10_000) {
            do {
                delay(20)
            } while (engine.state.value.busy)
            engine.state.value
        }

    /** Stream bodies queue for the /api/chat/completions dispatcher. */
    private fun enqueueStream(body: String) {
        synchronized(pendingStreams) {
            pendingStreams.addLast("text/event-stream; charset=utf-8" to body)
        }
    }

    /** Plain-JSON stream body (the jsonCompletion transport). */
    private fun enqueueJson(body: String) {
        synchronized(pendingStreams) {
            pendingStreams.addLast("application/json" to body)
        }
    }

    @Test
    fun firstMessageStreamsWithChatDiscovery() = runBlocking {
        enqueueStream(FIRST_MESSAGE)
        val (engine, store) = newEngine()
        engine.load(null)
        engine.send("hi", "m1")
        val final = awaitIdle(engine)

        // chat:list revealed the server-managed chat id
        assertEquals("chat-77", final.chatId)
        val assistant = final.messages.last { it.role == "assistant" }
        assertEquals("Hello, world!", assistant.markdown)
        assertEquals(listOf("What next?"), assistant.followUps)
        assertTrue(store.titles["chat-77"] == "New chat title")

        // persistence: nodes exist, current pointer set, assistant marked done
        assertTrue(store.messages.any { it.messageId == assistant.id && it.done && it.content == "Hello, world!" })
        assertEquals(assistant.id, store.currentMessages["chat-77"])
    }

    @Test
    fun jsonCompletionBodyFallsBack() = runBlocking {
        enqueueJson("""{"choices":[{"message":{"role":"assistant","content":"Whole answer"}}]}""")
                val (engine, _) = newEngine()
        engine.load(null)
        engine.send("hi", "m1")
        assertEquals("Whole answer", awaitIdle(engine).messages.last { it.role == "assistant" }.markdown)
    }

    @Test
    fun reasoningDeltasLandSeparately() = runBlocking {
        enqueueStream(REASONING)
                val (engine, _) = newEngine()
        engine.load(null)
        engine.send("hi", "m1")
        val final = awaitIdle(engine)
        val assistant = final.messages.last { it.role == "assistant" }
        assertEquals("Step one step two", assistant.reasoning)
        assertEquals("Final", assistant.markdown)
    }

    @Test
    fun regenerateCreatesSiblingBranch() = runBlocking {
        // First a normal exchange… (orders FIFO: stream, completed, stream, completed)
        enqueueStream(FIRST_MESSAGE_TWO_TURNS)
                val (engine, store) = newEngine()
        engine.load(null)
        engine.send("first question", "m1")
        awaitIdle(engine)
        // …then a regenerate — the sibling branch hangs off the same USER turn.
        enqueueStream(FIRST_SHORT)
                engine.regenerate("m1")
        val final = awaitIdle(engine)
        assertEquals("short regen", final.messages.last { it.role == "assistant" }.markdown)
        val assistants = store.messages.filter { it.role == "assistant" }
        assertEquals(2, assistants.size) // the original + the sibling
        // Tree fan-out: the user turn now has two assistant children.
        val userNode = store.messages.single { it.role == "user" }
        assertEquals(2, userNode.childrenIds.size)
    }


    private companion object {
        val FIRST_MESSAGE = """
            data: {"type":"chat:list","data":{"chat_id":"chat-77","folder_id":null}}

            data: {"choices":[{"delta":{"content":"Hello"}}]}

            data: {"choices":[{"delta":{"content":", world!"}}]}

            data: {"type":"chat:message:follow_ups","data":{"follow_ups":["What next?"]}}

            data: {"type":"chat:title","data":"New chat title"}

            data: [DONE]

        """.trimIndent() + "\n"

        val REASONING = """
            data: {"choices":[{"delta":{"reasoning_content":"Step one "}}]}

            data: {"choices":[{"delta":{"thinking":"step two"}}]}

            data: {"choices":[{"delta":{"content":"Final"}}]}

            data: [DONE]

        """.trimIndent() + "\n"

        val FIRST_MESSAGE_TWO_TURNS = """
            data: {"type":"chat:list","data":{"chat_id":"chat-88","folder_id":null}}

            data: {"choices":[{"delta":{"content":"First answer"}}]}

            data: {"choices":[{"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent() + "\n"

        val FIRST_SHORT = """
            data: {"choices":[{"delta":{"content":"short regen"}}]}

            data: {"choices":[{"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent() + "\n"
    }
}

/** In-memory [ChatStore] for engine tests (no Room on the JVM). */
private class InMemoryChatStore : ChatStore {
    val messages = ArrayList<MessageEntity>()
    val titles = HashMap<String, String>()
    val tags = HashMap<String, List<String>>()
    val currentMessages = HashMap<String, String>()
    val chats = LinkedHashMap<String, ChatPayload>()

    override suspend fun insertNode(message: MessageEntity) {
        messages.add(message)
        // Same fan-out contract as the Room store: parents learn their children.
        message.parentId?.takeIf { it != message.messageId }?.let { parentId ->
            messages.replaceAll {
                if (it.messageId == parentId && !it.childrenIds.contains(message.messageId)) {
                    it.copy(childrenIds = it.childrenIds + message.messageId)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun setCurrentMessage(chatId: String, messageId: String) {
        currentMessages[chatId] = messageId
    }

    override suspend fun setChatTitle(chatId: String, title: String) {
        titles[chatId] = title
    }

    override suspend fun setChatTags(chatId: String, tags: List<String>) {
        this.tags[chatId] = tags
    }

    override suspend fun setDone(messageId: String, done: Boolean) {
        messages.replaceAll { if (it.messageId == messageId) it.copy(done = done) else it }
    }

    override suspend fun updateAssistantContent(
        chatId: String,
        messageId: String,
        content: String,
        outputJson: String?,
        done: Boolean,
        timestamp: Long,
    ) {
        setDone(messageId, done)
        messages.replaceAll {
            if (it.messageId == messageId) it.copy(content = content, outputJson = outputJson) else it
        }
    }

    override suspend fun activePath(chatId: String): List<MessageEntity> =
        currentMessages[chatId]?.let { current ->
            val byId = messages.associateBy { it.messageId }
            buildList {
                var cursor = byId[current]
                while (cursor != null) {
                    add(cursor)
                    cursor = cursor.parentId?.let { byId[it] }
                }
            }.asReversed()
        } ?: emptyList()

    override suspend fun messages(chatId: String): List<MessageEntity> = messages.toList()

    override suspend fun fetchServerTree(chatId: String): dev.beacon.core.api.dto.ChatPayload? = chats[chatId]

    override suspend fun upsertServerChat(payload: ChatPayload) {
        chats[payload.id] = payload
    }

    override suspend fun refreshServerChat(chatId: String): Boolean =
        chats.containsKey(chatId)
}