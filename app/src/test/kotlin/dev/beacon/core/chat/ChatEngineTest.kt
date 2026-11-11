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
    private val recordedBodies = ArrayDeque<String>()
    private val dispatcher = object : mockwebserver3.Dispatcher() {
        override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
            if (request.url.encodedPath == "/api/chat/completions") {
                request.body?.let { synchronized(recordedBodies) { recordedBodies.addLast(it.utf8()) } }
                val next = synchronized(pendingStreams) { pendingStreams.removeFirstOrNull() }
                    ?: return MockResponse.Builder().code(404).build()
                if (next.first == "HANG") {
                    // A true hang: headers arrive, the body NEVER yields a byte
                    // (§9.1 flick rescue's dead-transfer case).
                    return MockResponse.Builder().code(200)
                        .addHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .onResponseBody(mockwebserver3.SocketEffect.Stall)
                        .build()
                }
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
    fun tagsEventUpdatesEngineStateAndStore() = runBlocking {
        // chat:tags envelope: {"type":"chat:tags","data":[…]} (middleware.py:3694-3715)
        enqueueStream(
            """
            data: {"type":"chat:list","data":{"chat_id":"chat-77","folder_id":null}}

            data: {"choices":[{"delta":{"content":"Tagged"}}]}

            data: {"type":"chat:tags","data":["foldable","ui"]}

            data: [DONE]
            """.trimIndent() + "\n",
        )
        val (engine, store) = newEngine()
        engine.load(null)
        engine.send("hi", "m1")
        val final = awaitIdle(engine)
        assertEquals(listOf("foldable", "ui"), final.tags)
        assertEquals(listOf("foldable", "ui"), store.chatTags("chat-77"))
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

    /** A stalled-socket response for the completions route (a true hang). */
    private fun enqueueHang() {
        synchronized(pendingStreams) {
            pendingStreams.addLast("HANG" to "")
        }
    }

    @Test
    fun flickWhileHungAbortsAndResyncs() = runBlocking {
        // Warm one completed exchange so the engine owns a chat id (the
        // rescue posts /api/tasks/chat/{id}/stop + re-fetches the tree).
        enqueueStream(FIRST_MESSAGE)
        val (engine, store) = newEngine()
        engine.load(null)
        engine.send("hi", "m1")
        awaitIdle(engine)

        // A second send whose stream NEVER yields a byte: busy forever.
        enqueueHang()
        engine.send("hi again", "m1")
        withTimeout(5_000) {
            while (!engine.state.value.busy) delay(10)
        }

        // (a) LIVE-stream immunity: a flick with a long silence threshold
        // is ignored — the stream stays the source of truth (§9.1).
        assertTrue(!engine.flickSync(hangSilenceMs = 60_000L))
        assertTrue(engine.state.value.busy)

        // (b) Hang rescue: with the silence crossed, the flick aborts the
        // dead transfer (stop semantics — partial kept) and re-syncs.
        engine.flickSync(hangSilenceMs = 0L)
        val state = awaitIdle(engine)
        assertTrue(!state.busy)
        assertTrue(state.streamingMessageId == null)

        // The rescue hit the wire: the chat-level stop route (+ tree fetch).
        val paths = mutableListOf<String>()
        withTimeout(5_000) {
            while (paths.size < 6) {
                val req = server.takeRequest(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                paths.add(req.url.encodedPath)
            }
        }
        assertTrue("expected the stop route in $paths", paths.any { it.startsWith("/api/tasks/chat/") && it.endsWith("/stop") })

        // (c) The rescue kept the conversation intact.
        assertTrue(engine.state.value.messages.any { it.role == "assistant" })
        assertTrue(store.messages.isNotEmpty())
    }

    @Test
    fun firstTurnOfAppCreatedChatStillRequestsTitleGeneration() = runBlocking {
        // The app creates the chat via POST /chats/new BEFORE the first send
        // (the web's initChatHandler) — so chat_id is already set and the
        // old isNewChat gate never fired. The web's actual rule is FIRST
        // TURN (Chat.svelte:4491-4497) — user report 2026-11-06: app-started
        // chats never got LLM-generated titles.
        val (engine, store) = newEngine()
        store.fabricateNewChat = dev.beacon.core.api.dto.ChatPayload(id = "chat-app-1", title = "New Chat")
        enqueueStream(FIRST_SHORT)
        engine.load(null)
        engine.send("hi", "m1")
        awaitIdle(engine)
        val first = synchronized(recordedBodies) { recordedBodies.removeFirst() }
        assertTrue(first, first.contains("\"chat_id\":\"chat-app-1\""))
        assertTrue(first, first.contains("\"title_generation\":true"))
        assertTrue(first, first.contains("\"tags_generation\":true"))
        // The wire body must carry the user text exactly TWICE: once in the
        // messages array, once in user_message — never three (2026-11-06: the
        // optimistic paint + the projection append duplicated it in messages,
        // and LLMs answered with duplicated text).
        assertEquals(first, 2, first.split("\"content\":\"hi\"").size - 1)

        // Second turn in the same chat: no title/tags generation.
        enqueueStream(FIRST_SHORT)
        engine.send("and again", "m1")
        awaitIdle(engine)
        val second = synchronized(recordedBodies) { recordedBodies.removeFirst() }
        assertTrue(second, !second.contains("\"title_generation\":true"))
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


    @Test
    fun regenerateAtMidChatWipesOnlyAfterThePressedMessage() = runBlocking {
        // Two turns: [u1, a1, u2, a2] — then ↻ on the FIRST assistant (a1).
        enqueueStream(FIRST_MESSAGE_TWO_TURNS)
        val (engine, store) = newEngine()
        engine.load(null)
        engine.send("q1", "m1")
        awaitIdle(engine)
        enqueueStream(SECOND_TURN)
        engine.send("q2", "m1")
        val mid = awaitIdle(engine)
        assertEquals(listOf("user", "assistant", "user", "assistant"), mid.messages.map { it.role })
        val a1 = mid.messages[1].id

        enqueueStream(FIRST_SHORT)
        engine.regenerateAt(a1, "m1")
        val final = awaitIdle(engine)
        // Everything BEFORE the pressed message stays; the pressed assistant and
        // everything after it are replaced by the new sibling.
        assertEquals(listOf("user", "assistant"), final.messages.map { it.role })
        assertEquals("q1", final.messages[0].markdown)
        assertEquals("short regen", final.messages[1].markdown)
        // The fork hangs off the pressed turn's user message, so a later repaint
        // from Room keeps the prefix instead of collapsing to the fork.
        val fork = store.messages.single { it.messageId != a1 && it.role == "assistant" && it.messageId != mid.messages[3].id }
        assertEquals(mid.messages[0].id, fork.parentId)
    }

    @Test
    fun syncAfterRegenerateKeepsEarlierMessagesWhenServerOrphansFork() = runBlocking {
        // Regenerate the only turn, then a sync whose server tree carries the
        // fork with parentId=null (the streaming-upsert orphan, models/chats.py:1134).
        // The visible thread must still include the user turn — not collapse to
        // the fork alone (user report: regenerate wiped all previous messages).
        enqueueStream(FIRST_MESSAGE_TWO_TURNS)
        val (engine, store) = newEngine()
        engine.load(null)
        engine.send("q1", "m1")
        val first = awaitIdle(engine)
        val u1 = first.messages[0].id
        val a1 = first.messages[1].id

        enqueueStream(FIRST_SHORT)
        engine.regenerateAt(a1, "m1")
        val regen = awaitIdle(engine)
        val fork = regen.messages[1].id

        store.chats["chat-88"] = dev.beacon.core.api.dto.ChatPayload(
            id = "chat-88",
            title = "t",
            chat = dev.beacon.core.api.dto.ChatStorage(
                history = dev.beacon.core.api.dto.ChatHistory(
                    messages = mapOf(
                        u1 to dev.beacon.core.api.dto.StoredMessage(id = u1, parentId = null, role = "user", content = "q1", done = true),
                        a1 to dev.beacon.core.api.dto.StoredMessage(id = a1, parentId = u1, role = "assistant", content = "First answer", done = true),
                        fork to dev.beacon.core.api.dto.StoredMessage(
                            id = fork,
                            parentId = null, // server orphaned the fork
                            role = "assistant",
                            content = "short regen",
                            done = true,
                            output = kotlinx.serialization.json.buildJsonArray { },
                        ),
                    ),
                    currentId = fork,
                ),
            ),
        )
        engine.sync()
        val after = engine.state.value.messages
        assertTrue("expected the user turn to survive sync, got $after", after.any { it.id == u1 && it.role == "user" })
        assertTrue(after.any { it.id == fork })
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

        val SECOND_TURN = """
            data: {"choices":[{"delta":{"content":"Second answer"}}]}

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
class InMemoryChatStore : ChatStore {
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

    override suspend fun chatTitle(chatId: String): String? = titles[chatId]

    override suspend fun chatTags(chatId: String): List<String> = tags[chatId] ?: emptyList()

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

    /** Default: no /chats/new endpoint (the 0.9-era servers the fixtures
     *  captured) — the fresh turn keeps the chat-less M1 flow (chat:list
     *  discovery). Tests exercising the create-first flow override this. */
    /** When set, createServerChat fabricates this payload (the app-first flow). */
    var fabricateNewChat: dev.beacon.core.api.dto.ChatPayload? = null
    override suspend fun createServerChat(title: String, modelId: String): dev.beacon.core.api.dto.ChatPayload? = fabricateNewChat

    override suspend fun serverVersion(): String? = "0.12.5"

    override suspend fun saveServerTree(chatId: String, currentId: String, modelId: String?): Boolean = true

    override suspend fun chatCurrentMessageId(chatId: String): String? =
        currentMessages[chatId]
}