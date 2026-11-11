package dev.beacon.core.api

import dev.beacon.core.api.dto.ChatPayload
import dev.beacon.core.api.dto.ChatsListRow
import dev.beacon.core.api.dto.NewChatBody
import dev.beacon.core.network.await
import dev.beacon.core.network.decodeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Joins an instance origin and a canonical path (leading slash optional, no trailing slash). */
internal fun joinUrl(baseUrl: String, path: String): String {
    val trimmedBase = baseUrl.trimEnd('/')
    val cleanPath = if (path.startsWith("/")) path else "/$path"
    return trimmedBase + cleanPath
}

// ── M1 · chats table (verified: docs/protocol-notes.md) ─────────────────────
// list:        GET  /api/v1/chats/?page=N (chats.py:261; 60/page, sort updated_at desc)
// search:      GET  /api/v1/chats/search?q=… (chats.py:900; ChatTitleIdResponse rows, same shape as list)
// pinned rows: GET  /api/v1/chats/pinned (chats.py:994; list rows lack `pinned`)
// full tree:   GET  /api/v1/chats/{id}  (chats.py:1380)
// create:      POST /api/v1/chats/new (chats.py:789; ChatForm {chat, variables?, folder_id?})
// delete:      DELETE /api/v1/chats/{id} (chats.py:1677)
// pin:         POST /api/v1/chats/{id}/pin (chats.py:1751)
// archive:     POST /api/v1/chats/{id}/archive (chats.py:2048)

@Singleton
class ChatsApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val source: InstanceSource,
) {
    private val rows = ListSerializer(ChatsListRow.serializer())
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun list(page: Int = 1): List<ChatsListRow> = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/?page=$page")).get().build(),
        ).await().decodeJson(rows, json)
    }

    /**
     * GET /api/v1/chats/search?q=… — server-side title search
     * (chats.py:900, verified via docs/interface-mapping.md; response rows are
     * the same ChatTitleIdResponse shape as the list endpoint).
     */
    suspend fun search(q: String): List<ChatsListRow> = withContext(Dispatchers.IO) {
        val enc = java.net.URLEncoder.encode(q, "utf-8")
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/search?q=$enc")).get().build(),
        ).await().decodeJson(rows, json)
    }

    suspend fun pinned(): List<ChatsListRow> = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/pinned")).get().build(),
        ).await().decodeJson(rows, json)
    }

    suspend fun full(chatId: String): ChatPayload = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/$chatId")).get().build(),
        ).await().decodeJson(ChatPayload.serializer(), json)
    }

    suspend fun create(body: NewChatBody): ChatPayload = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(joinUrl(instance(), "/api/v1/chats/new"))
                .post(json.encodeToString(body).toRequestBody(jsonType))
                .build(),
        ).await().decodeJson(ChatPayload.serializer(), json)
    }

    suspend fun delete(chatId: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/$chatId")).delete().build(),
        ).await().use { it.isSuccessful }
    }

    suspend fun pin(chatId: String): ChatPayload = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(joinUrl(instance(), "/api/v1/chats/$chatId/pin"))
                .post("{}".toRequestBody(jsonType))
                .build(),
        ).await().decodeJson(ChatPayload.serializer(), json)
    }

    suspend fun archive(chatId: String): ChatPayload = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(joinUrl(instance(), "/api/v1/chats/$chatId/archive"))
                .post("{}".toRequestBody(jsonType))
                .build(),
        ).await().decodeJson(ChatPayload.serializer(), json)
    }

    /**
     * POST /{id} — the FULL tree save (ChatForm `{chat: {models, history,
     * messages}}`; chats.py:1415-1466; the web's saveChatHandler
     * Chat.svelte:4877-4890 does exactly this per send, which is why its
     * regenerate forks land server-side with a correct parent).
     */
    suspend fun saveTree(chatId: String, chat: kotlinx.serialization.json.JsonObject): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url(joinUrl(instance(), "/api/v1/chats/$chatId"))
                        .post(json.encodeToString(
                            kotlinx.serialization.json.JsonObject.serializer(),
                            kotlinx.serialization.json.buildJsonObject { put("chat", chat) },
                        ).toRequestBody(jsonType))
                        .build(),
                ).await().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    private fun instance(): String =
        source.instanceUrl()
            ?: throw OwuiApiException(0, null, "No connected instance")
}