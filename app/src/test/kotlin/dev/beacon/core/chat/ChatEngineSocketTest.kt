package dev.beacon.core.chat

import dev.beacon.core.api.ChatsApi
import dev.beacon.core.api.InstanceSource
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.network.socket.ChatSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The taskSocket leg (M4-d3, agent.md §6.1): with a socket session id in the
 * request, the server answers {status, task_ids, chat_id} (main.py:1916) and
 * the stream rides socket `events`; the engine folds them and ends on the
 * terminal chat:completion {done:true} (middleware.py:6341). A socket that
 * never delivers recovers by refetching the server tree (§9.2).
 */
class ChatEngineSocketTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private lateinit var server: mockwebserver3.MockWebServer
    private val pendingStreams = ArrayDeque<Pair<String, String>>()
    private val recordedBodies = ArrayDeque<String>()

    private val dispatcher = object : mockwebserver3.Dispatcher() {
        override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
            if (request.url.encodedPath == "/api/chat/completions") {
                request.body?.let { synchronized(recordedBodies) { recordedBodies.addLast(it.utf8()) } }
                val next = synchronized(pendingStreams) { pendingStreams.removeFirstOrNull() }
                    ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(200).addHeader("Content-Type", next.first).body(next.second).build()
            }
            return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build()
        }
    }

    @Before
    fun start() {
        server = mockwebserver3.MockWebServer()
        server.dispatcher = dispatcher
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    /** Fakes the transport seam: connected + manual emission control.
     *  replay=64: events emitted before the engine's collector subscribes are
     *  never lost (in production the collector subscribes before the POST). */
    private class FakeSocket : ChatSocket {
        val flow = MutableSharedFlow<dev.beacon.core.network.socket.SocketEnvelope>(replay = 64, extraBufferCapacity = 64)
        private var connected = false
        fun goLive() {
            connected = true
        }

        override suspend fun ensureConnected(timeoutMs: Long): Boolean = connected
        override fun sessionId(): String? = if (connected) "socket-id-1" else null
        override val events: SharedFlow<dev.beacon.core.network.socket.SocketEnvelope> = flow
    }

    private fun newEngine(socket: ChatSocket?): Pair<ChatEngine, InMemoryChatStore> {
        val root = server.url("/").toString().trimEnd('/')
        val client = OkHttpClient()
        val api = OwuiApi(client, json, InstanceSource { root })
        val chatsApi = ChatsApi(client, json, InstanceSource { root })
        val store = InMemoryChatStore()
        return ChatEngine(api, store, json, appContext = null, socket) to store
    }

    @Test
    fun taskIdsResponseFoldsSocketEvents() = runBlocking {
        synchronized(pendingStreams) {
            pendingStreams.addLast(
                "application/json" to
                    """{"status":true,"task_ids":["t1"],"chat_id":"live-chat-1"}""",
            )
        }
        val fake = FakeSocket().apply { goLive() }
        val (engine, store) = newEngine(fake)
        engine.load(null)
        engine.send("hi", "m1")
        // The server echoes the CLIENT-generated assistant id in envelope messageIds.
        val assistantId = withTimeout(5_000) {
            while (engine.state.value.streamingMessageId == null) delay(10)
            engine.state.value.streamingMessageId!!
        }
        // Stream deltas then the terminal, exactly as middleware emits them (§5467/:6372).
        fake.flow.emit(
            dev.beacon.core.network.socket.SocketEnvelope(
                chatId = "live-chat-1", messageId = assistantId,
                data = buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("response.output_text.delta"))
                    put("delta", kotlinx.serialization.json.JsonPrimitive("Sock"))
                },
            ),
        )
        delay(60)
        fake.flow.emit(
            dev.beacon.core.network.socket.SocketEnvelope(
                chatId = "live-chat-1", messageId = assistantId,
                data = buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("chat:completion"))
                    put("data", buildJsonObject {
                        put("done", kotlinx.serialization.json.JsonPrimitive(true))
                        put("output", kotlinx.serialization.json.Json.parseToJsonElement(
                            """[{"type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Socket answer"}]}]""",
                        ))
                        put("title", kotlinx.serialization.json.JsonPrimitive("Sock chat"))
                    })
                },
            ),
        )
        val final = withTimeout(10_000) {
            do {
                delay(20)
            } while (engine.state.value.busy)
            engine.state.value
        }

        val assistantFinal = final.messages.last { it.role == "assistant" }
        assertEquals(
            "Socket answer [got: ${final.messages.map { it.role + "#" + it.markdown.take(30) }}]",
            "Socket answer",
            assistantFinal.markdown,
        )
        assertEquals("live-chat-1", final.chatId)
        // session_id rode the request when the socket was up:
        assertTrue(recordedBodies.first().contains("\"session_id\":\"socket-id-1\""))
        // and the terminal title persisted:
        assertEquals("Sock chat", store.titles["live-chat-1"])
    }

    @Test
    fun socketDown_fallsBackToPlainSse() = runBlocking {
        val (engine, _) = newEngine(FakeSocket()) // never live
        engine.load(null)
        engine.send("hi", "m1")
        // Drain the request body: no session_id → the server keeps streaming SSE.
        val first = withTimeout(10_000) {
            while (recordedBodies.isEmpty()) delay(10)
            recordedBodies.removeFirst()
        }
        assertTrue(!first.contains("session_id"))
        withTimeout(10_000) {
            while (engine.state.value.busy) delay(20)
        }
    }
}