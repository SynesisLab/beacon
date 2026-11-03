package dev.beacon.core.network.socket

import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OWUI Socket.IO session (M4-d3). Protocol ground truth (open-webui clone):
 *
 * - Mount: FastAPI mounts the socket app at `/ws` → path `/ws/socket.io`
 *   (main.py:873).
 * - Handshake auth: `{token: <jwt/api-key>}` in the connect `auth` object;
 *   `get_verified_user_by_token` (utils/auth.py:576-586) resolves the user and
 *   the server enters room `user:{user_id}` (socket/main.py:611-641).
 * - Legacy path: `user-join` with `{auth:{token}}` (socket/main.py:638-676);
 *   Conduit emits it after connect — we do the same.
 * - Server→client events ride the `events` event into the user room as
 *   `{chat_id, message_id, data:{type,…}}` envelopes (socket/main.py:1626-1673).
 * - Heartbeat: `@sio.on('heartbeat')` (socket/main.py:723); Conduit pings every
 *   30s. We match.
 * - Blocking `sio.call` requests aimed at THIS session (execute /
 *   execute:python / request:terminal / request:elicitation) must be answered
 *   or a server-side tool waits forever; M4 answers Conduit's refusals, full
 *   confirmations dialogs are a later slice.
 */
sealed interface SocketHealth {
    data object Idle : SocketHealth
    data object Connecting : SocketHealth
    data class Connected(val sessionId: String) : SocketHealth
    data class Failed(val reason: String) : SocketHealth
}

/** One server event, exactly as the server sent it. Carries the [ack] for
 *  server→client CALLS (request:user_input etc.): the receiver answers via
 *  [reply] / [cancelReply] — the socket.io ack responds once, any thread. */
data class SocketEnvelope(
    val chatId: String?,
    val messageId: String?,
    val data: JsonObject,
    private val ack: io.socket.client.Ack? = null,
) {
    fun canReply(): Boolean = ack != null

    /** Replies to a server CALL with a JSON payload (builtin.py:639-656 contract). */
    fun reply(payload: JsonObject): Boolean {
        val fn = ack ?: return false
        return runCatching {
            fn.call(orgJson(payload))
            true
        }.getOrDefault(false)
    }

    fun cancelReply(): Boolean = reply(buildJsonObject {
        put("status", "cancelled")
    })

    private fun orgJson(any: Any?): org.json.JSONObject {
        val obj = org.json.JSONObject()
        for ((k, v) in (any as? Map<*, *>).orEmpty()) {
            obj.put(k.toString(), when (v) {
                null -> org.json.JSONObject.NULL
                is Map<*, *> -> orgJson(v)
                is List<*> -> org.json.JSONArray(v.map { if (it is Map<*, *>) orgJson(it) else (it ?: org.json.JSONObject.NULL) })
                else -> v
            })
        }
        return obj
    }
}

/**
 * Wire-format lesson (captured 2026-11-03, in-app j.u.l trace): the Java
 * client's encoder does NOT convert Kotlin/java maps — a nested map encodes as
 * its Java toString() (`20["user-join","{auth={…}}"]`), which the server then
 * reads as a STRING and silently drops (`if not auth or 'token' not in auth:
 * return`). EVERY payload we emit must be org.json objects.
 */
private fun orgJson(any: Any?): org.json.JSONObject {
    val obj = org.json.JSONObject()
    for ((k, v) in (any as? Map<*, *>).orEmpty()) {
        obj.put(k.toString(), when (v) {
            null -> org.json.JSONObject.NULL
            is Map<*, *> -> orgJson(v)
            is List<*> -> org.json.JSONArray(v.map { if (it is Map<*, *>) orgJson(it) else (it ?: org.json.JSONObject.NULL) })
            is Array<*> -> org.json.JSONArray(v.map { if (it is Map<*, *>) orgJson(it) else (it ?: org.json.JSONObject.NULL) })
            else -> v
        })
    }
    return obj
}

@Singleton
class OwuiSocket @Inject constructor() {

    private val _health = MutableStateFlow<SocketHealth>(SocketHealth.Idle)
    val health: StateFlow<SocketHealth> = _health

    private val _events = MutableSharedFlow<SocketEnvelope>(
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** All chat/channel/note events, raw. The engine/UI folds what it owns. */
    val events: SharedFlow<SocketEnvelope> = _events

    private var socket: Socket? = null
    /** origin|token-tail this session was opened for (reconnect detection). */
    private var openedFor: String? = null
    private var heartbeatJob: Job? = null

    val sessionId: String?
        get() = (_health.value as? SocketHealth.Connected)?.sessionId

    /** Whether the current connection serves this origin+token pair. */
    fun matchesInstance(instanceUrl: String, token: String): Boolean =
        openedFor == key(instanceUrl, token) && _health.value is SocketHealth.Connected

    fun isConnected(): Boolean = _health.value is SocketHealth.Connected

    /**
     * Connects (idempotent per origin+token) and waits up to [timeoutMs] for
     * the handshake. Returns true when connected. The Java client keeps its
     * own reconnection loop afterwards.
     */
    suspend fun ensureConnected(instanceUrl: String, bearerToken: String, timeoutMs: Long = 5_000): Boolean {
        if (matchesInstance(instanceUrl, bearerToken)) return true
        val nowKey = key(instanceUrl, bearerToken)
        if (_health.value == SocketHealth.Connecting && openedFor == nowKey) {
            // A handshake is already in flight for this origin — just wait.
            return waitConnected(timeoutMs)
        }
        disconnect(reason = null)
        openedFor = nowKey
        _health.value = SocketHealth.Connecting
        if (!open(instanceUrl, bearerToken)) return false
        return waitConnected(timeoutMs)
    }

    /** Waits up to timeoutMs for CONNECT / first failure. */
    suspend fun waitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            var ok = false
            health.first { h ->
                when (h) {
                    is SocketHealth.Connected -> { ok = true; true }
                    is SocketHealth.Failed -> { ok = false; true }
                    else -> false
                }
            }
            ok
        } ?: false

    fun disconnect(reason: String? = "client disconnect") {
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.let { s ->
            runCatching { s.off() }
            runCatching { s.disconnect() }
        }
        socket = null
        openedFor = null
        _health.value = SocketHealth.Idle
    }

    private fun key(instanceUrl: String, token: String) = "$instanceUrl|${token.takeLast(12)}"

    private fun open(instanceUrl: String, bearerToken: String): Boolean = try {
        val options = IO.Options().apply {
            // main.py:873 — FastAPI mounts the socket app under /ws.
            path = "/ws/socket.io"
            // socket/main.py:611-618 — the handshake auth token.
            // The server runs WEBSOCKET-ONLY by default (env.py:473
            // ENABLE_WEBSOCKET_SUPPORT=True → transports=['websocket'],
            // allow_upgrades=false — polls are rejected with 400 "Invalid
            // transport"), so websocket-only avoids a dead polling first try.
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionDelay = 1_000L
            reconnectionDelayMax = 60_000L
            timeout = 20_000
            forceNew = true
        }
        options.auth = mapOf("token" to bearerToken)
        val client = IO.socket(instanceUrl.trimEnd('/'), options).apply {
            on(Socket.EVENT_CONNECT) {
                val sid = id()
                Timber.d("socket connected sid=%s", sid)
                _health.value = SocketHealth.Connected(sid)
                // Acked probe: user_join (socket/main.py:638-642) returns
                // {id, name} when the token resolves; a null result means the
                // session never enters its user room.
                emit(
                    "user-join",
                    orgJson(mapOf("auth" to mapOf("token" to bearerToken))),
                    io.socket.client.Ack { args ->
                        Timber.d("user-join ack=%s", args.joinToString { it.toString().take(80) })
                    },
                )
                startHeartbeat()
            }
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                val reason = args.firstOrNull()?.toString() ?: "connect error"
                Timber.w("socket connect error: %s", reason)
                _health.value = SocketHealth.Failed(reason)
            }
            on(Socket.EVENT_DISCONNECT) {
                Timber.d("socket disconnected")
                _health.value = SocketHealth.Failed("disconnected")
            }
            for (name in EVENT_NAMES) on(name) { args ->
                handleEvent(name, args)
            }
            connect()
        }
        socket = client
        true
    } catch (e: Exception) {
        socket = null
        _health.value = SocketHealth.Failed(e.message ?: "socket open failed")
        false
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        val scope = SupervisorJob()
        heartbeatJob = kotlinx.coroutines.CoroutineScope(scope + Dispatchers.IO).launch {
            while (true) {
                if (!isConnected()) break
                runCatching { socket?.emit("heartbeat", orgJson(emptyMap<String, Any>())) }
                delay(30_000)
            }
        }
    }

    /** Handles one server event; parses the {chat_id, message_id, data} envelope. */
    private fun handleEvent(name: String, args: Array<Any?>) {
        // Server→client CALLS (sio.call): the ack callback is the last arg.
        val ack = args.lastOrNull() as? io.socket.client.Ack
        val payloadArg = if (args.size >= 2) args[0] else args.firstOrNull()
        Timber.d("socket event %s args=%d", name, args.size)
        // The payload arrives as an org.json.JSONObject — the same wire lesson
        // as the outgoing side: it is NOT a kotlin Map, so convert FIRST.
        val obj = toTree(payloadArg) as? JsonObject ?: return
        val data = obj["data"] as? JsonObject ?: obj
        val type = (data["type"] as? JsonPrimitive)?.content

        // Blocking calls we do not support are refused immediately so the
        // server-side tool doesn't wait out its timeout. INTERACTIVE ones
        // (request:user_input) keep their ack and surface on the UI.
        if (ack != null && type in UNSUPPORTED_CALLS) {
            val reply = orgJson(UNSUPPORTED_CALLS[type])
            runCatching { ack.call(reply) }
        }

        val envelope = SocketEnvelope(
            chatId = (obj["chat_id"] as? JsonPrimitive)?.content,
            messageId = (obj["message_id"] as? JsonPrimitive)?.content,
            data = data,
            ack = ack.takeIf { type !in UNSUPPORTED_CALLS },
        )
        Timber.d("events envelope chat=%s msg=%s type=%s", envelope.chatId, envelope.messageId, type)
        _events.tryEmit(envelope)
    }

    fun emitUserJoin(token: String) {
        runCatching { socket?.emit("user-join", orgJson(mapOf("auth" to mapOf("token" to token)))) }
    }

    // ── mapping: org.json (Java client) → kotlinx Json ─────────────────────

    private fun toTree(any: Any?): JsonElement = when (any) {
        null -> JsonNull
        is String -> JsonPrimitive(any)
        is Boolean -> JsonPrimitive(any)
        is Number -> if (any is Int || any is Long) JsonPrimitive(any.toLong()) else JsonPrimitive(any.toDouble())
        is Map<*, *> -> JsonObject(
            any.entries.associate { (k, v) -> (k as? String ?: "?") to toTree(v) },
        )
        is List<*> -> JsonArray(any.map { toTree(it) })
        is Array<*> -> JsonArray(any.map { toTree(it) })
        is org.json.JSONArray -> JsonArray((0 until any.length()).map { toTree(any.opt(it)) })
        is org.json.JSONObject -> JsonObject(
            any.keys().asSequence().associateWith { key -> toTree(any.opt(key)) },
        )
        else -> JsonPrimitive(any.toString())
    }

    companion object {
        /** Server→client chat events (primary name today). */
        const val SERVER_EVENTS = "events"
        /** Conduit listens to both (older servers). */
        const val LEGACY_EVENTS = "chat-events"
        val EVENT_NAMES = listOf(SERVER_EVENTS, LEGACY_EVENTS, "events:channel", "channel-events")

        /**
         * Blocking host calls we refuse so the server-side tool completes
         * (Conduit: docs/open-webui-functions.md). The INTERACTIVE
         * `request:user_input` is answered by the chat UI instead.
         */
        val UNSUPPORTED_CALLS: Map<String, Map<String, Any?>> = mapOf(
            "execute" to mapOf(
                "error" to "Beacon does not support client-side JavaScript execution.",
                "client" to "beacon-android",
            ),
            "execute:python" to mapOf(
                "stdout" to "",
                "stderr" to "Beacon does not support client-side Python execution.",
                "result" to null,
            ),
            "request:terminal" to emptyMap<String, Any?>(),
            "request:terminal:state" to mapOf("connected" to false),
            "request:elicitation" to mapOf("action" to "cancel"),
            "execute:tool" to mapOf("error" to "Beacon does not support direct tool execution."),
            "voice:play_animation" to mapOf("status" to "unavailable"),
        )
    }
}