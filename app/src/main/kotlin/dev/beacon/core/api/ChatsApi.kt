package dev.beacon.core.api

import dev.beacon.core.api.dto.ChatPayload
import dev.beacon.core.api.dto.ChatsListRow
import dev.beacon.core.api.dto.NewChatBody
import dev.beacon.core.data.SessionStore
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

/**
 * Chat CRUD over /api/v1/chats (verified: docs/protocol-notes.md M1 · chats table).
 * M1 surface: list paging, full-tree fetch, create, delete, pin, archive.
 */
@Singleton
class ChatsApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val session: SessionStore,
) {
    private val rows = ListSerializer(ChatsListRow.serializer())

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Paged rows: GET /api/v1/chats/?page=N (60/page server-side, sort updated_at desc). */
    suspend fun list(page: Int = 1): List<ChatsListRow> = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/?page=$page")).get().build(),
        ).await().decodeJson(rows, json)
    }

    /** Pinned rows: GET /api/v1/chats/pinned (chats.py:994). */
    suspend fun pinned(): List<ChatsListRow> = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/pinned")).get().build(),
        ).await().decodeJson(rows, json)
    }

    /** Full tree: GET /api/v1/chats/{id} (chats.py:1380). */
    suspend fun full(chatId: String): ChatPayload = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/$chatId")).get().build(),
        ).await().decodeJson(ChatPayload.serializer(), json)
    }

    /** POST /api/v1/chats/new (chats.py:789). */
    suspend fun create(body: NewChatBody): ChatPayload = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(joinUrl(instance(), "/api/v1/chats/new"))
                .post(json.encodeToString(body).toRequestBody(jsonType))
                .build(),
        ).await().decodeJson(ChatPayload.serializer(), json)
    }

    /** DELETE /api/v1/chats/{id} → bool (chats.py:1677). */
    suspend fun delete(chatId: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(instance(), "/api/v1/chats/$chatId")).delete().build(),
        ).await().use { it.isSuccessful }
    }

    /** POST /api/v1/chats/{id}/pin (chats.py:1751) — returns the updated chat. */
    suspend fun pin(chatId: String): ChatPayload = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(joinUrl(instance(), "/api/v1/chats/$chatId/pin"))
                .post("{}".toRequestBody(jsonType))
                .build(),
        ).await().decodeJson(ChatPayload.serializer(), json)
    }

    /** POST /api/v1/chats/{id}/archive (chats.py:2048). */
    suspend fun archive(chatId: String): ChatPayload = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(joinUrl(instance(), "/api/v1/chats/$chatId/archive"))
                .post("{}".toRequestBody(jsonType))
                .build(),
        ).await().decodeJson(ChatPayload.serializer(), json)
    }

    private fun instance(): String =
        session.instanceUrlSnapshot()
            ?: throw OwuiApiException(0, null, "No connected instance")
}