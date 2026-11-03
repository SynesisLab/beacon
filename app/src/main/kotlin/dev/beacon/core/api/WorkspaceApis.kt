package dev.beacon.core.api

import dev.beacon.core.network.await
import dev.beacon.core.network.decodeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.beacon.core.api.dto.ChatPayload
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

// ── M4 drawer surfaces — list shapes verified in the clone (docs/protocol-notes.md) ──
// folders.py:92  FolderNameIdResponse {id, user_id, name, parent_id?, …}
// notes.py:113   NoteItemResponse {id, title, data?, is_pinned?, updated_at, …}
// channels.py:191 ChannelModel {id, name, description?, is_private?, …}
// files.py:485   FileListResponse {items: [FileModelResponse{id, filename, …}], total}
// prompts.py     PromptModel {id?, command, name, content}
// tools.py       ToolModel {id, name, …}
// knowledge.py   KnowledgeModel {id, name, …}

@Serializable
data class FolderRow(val id: String, val name: String, val parent_id: String? = null, val unread_count: Int = 0)

@Serializable
data class NoteRow(val id: String, val title: String, val is_pinned: Boolean? = null, val updated_at: Long? = null)

@Serializable
data class ChannelRow(val id: String, val name: String, val description: String? = null, val is_private: Boolean? = null)

@Serializable
data class FileRow(val id: String, val filename: String, val created_at: Long? = null)

@Serializable
data class PromptRow(val id: String? = null, val command: String, val name: String, val content: String? = null)

@Serializable
data class ToolRow(val id: String, val name: String)

@Serializable
data class KnowledgeRow(val id: String, val name: String, val description: String? = null)

/** knowledge.py:139-141 — KnowledgeAccessListResponse. */
@Serializable
data class KnowledgePage(val items: List<KnowledgeRow> = emptyList(), val total: Int = 0)

/** GET /api/v1/files/ → {items, total} (files.py:485-…). */
@Serializable
data class FilesPage(val items: List<FileRow> = emptyList(), val total: Int = 0)

@Singleton
class WorkspaceApis @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val source: InstanceSource,
) {
    private fun get(path: String) = Request.Builder().url(joinUrl(requireInstance(), path)).get().build()

    suspend fun folders(): List<FolderRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/folders/")).await().decodeJson(ListSerializer(FolderRow.serializer()), json)
    }

    suspend fun notes(): List<NoteRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/notes/")).await().decodeJson(ListSerializer(NoteRow.serializer()), json)
    }

    suspend fun channels(): List<ChannelRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/channels/")).await().decodeJson(ListSerializer(ChannelRow.serializer()), json)
    }

    suspend fun files(): List<FileRow> = withContext(Dispatchers.IO) {
        // content=false: the route ships file CONTENT by default (slow, blobs);
        // the list only needs ids/names (files.py:485-490 verified).
        client.newCall(get("/api/v1/files/?page=1&content=false")).await()
            .decodeJson(FilesPage.serializer(), json).items
    }

    suspend fun prompts(): List<PromptRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/prompts/")).await().decodeJson(ListSerializer(PromptRow.serializer()), json)
    }

    suspend fun tools(): List<ToolRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/tools/")).await().decodeJson(ListSerializer(ToolRow.serializer()), json)
    }

    /** GET /api/v1/knowledge/ → KnowledgeAccessListResponse {items, total} (routers/knowledge.py:139). */
    suspend fun knowledge(): List<KnowledgeRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/knowledge/")).await()
            .decodeJson(KnowledgePage.serializer(), json).items
    }

    // ── M4-d2 write-flows (verified: folders.py:150/:335/:710; notes.py:237/:529;
    // channels.py:988-1484 — MessageForm {content, temp_id}) ──────────────────

    suspend fun createFolder(name: String) {
        val body = buildJsonObject { put("name", name) }
        postJson("/api/v1/folders/", json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
    }

    suspend fun renameFolder(folderId: String, name: String) {
        val body = buildJsonObject { put("name", name) }
        postJson("/api/v1/folders/$folderId/update", json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
    }

    suspend fun deleteFolder(folderId: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(requireInstance(), "/api/v1/folders/$folderId")).delete().build(),
        ).await().use { it.isSuccessful }
    }

    /** The folder's chats: GET /api/v1/chats/folder/{id} (chats.py:944-959). */
    suspend fun folderChats(folderId: String): List<ChatPayload> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/chats/folder/$folderId")).await()
            .decodeJson(ListSerializer(ChatPayload.serializer()), json)
    }

    /** POST /api/v1/notes/create {title, data{json: tiptap doc}} (notes.py:237). */
    suspend fun createNote(title: String, plainContent: String): String? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("title", title)
            put("data", NoteDoc.doc(plainContent))
        }
        client.newCall(
            Request.Builder().url(joinUrl(requireInstance(), "/api/v1/notes/create"))
                .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body)
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build(),
        ).await().use {
            if (!it.isSuccessful) {
                return@withContext null
            }
            it.body?.string()?.let { text ->
                (json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)
                    ?.get("id")?.let { id -> (id as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
        }
    }

    /** POST /api/v1/notes/{id}/update (notes.py:529); content replaces the doc. */
    suspend fun updateNote(noteId: String, title: String?, plainContent: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                title?.let { put("title", it) }
                put("data", NoteDoc.doc(plainContent))
            }
            client.newCall(
                Request.Builder().url(joinUrl(requireInstance(), "/api/v1/notes/$noteId/update"))
                    .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body)
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build(),
            ).await().use { it.isSuccessful }
        }

    /** GET /api/v1/notes/{id} — the full note (decode the doc to plain text). */
    suspend fun note(noteId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/notes/$noteId")).await().use { response ->
            if (!response.isSuccessful) return@withContext null
            val text = response.body?.string() ?: return@withContext null
            val obj = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                ?: return@withContext null
            val title = (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val content = obj["data"]?.let { NoteDoc.plainText(it) } ?: ""
            title to content
        }
    }

    /** GET /api/v1/channels/{id}/messages (channels.py:988). */
    suspend fun channelMessages(channelId: String): List<ChannelMessageRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/channels/$channelId/messages")).await()
            .decodeJson(ListSerializer(ChannelMessageRow.serializer()), json)
    }

    /** POST /api/v1/channels/{id}/messages/post (channels.py:1484; MessageForm). */
    suspend fun postChannelMessage(channelId: String, content: String, tempId: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("content", content)
                put("temp_id", tempId)
            }
            client.newCall(
                Request.Builder().url(joinUrl(requireInstance(), "/api/v1/channels/$channelId/messages/post"))
                    .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body)
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build(),
            ).await().use { it.isSuccessful }
        }

    private suspend fun postJson(path: String, body: String) {
        client.newCall(
            Request.Builder().url(joinUrl(requireInstance(), path))
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build(),
        ).await().use { }
    }

    private fun requireInstance(): String =
        source.instanceUrl() ?: throw OwuiApiException(0, null, "No connected instance")
}

/** Open WebUI notes store TipTap JSON docs under `data.json` — a minimal round-trip. */
object NoteDoc {
    /** Builds a single-paragraph/plain-text doc (the minimal valid TipTap shape). */
    fun doc(plain: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("json", buildJsonObject {
            put("type", "doc")
            put("content", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("type", "paragraph")
                put("content", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                    put("type", "text")
                    put("text", plain)
                })))
            })))
        })
    }

    /** Walks the doc's content nodes and joins plain text. */
    fun plainText(element: kotlinx.serialization.json.JsonElement): String = try {
        val jsonDoc = element as? kotlinx.serialization.json.JsonObject
        val doc = jsonDoc?.get("json")
        extract(doc ?: element)
    } catch (_: Exception) {
        ""
    }

    private fun extract(element: kotlinx.serialization.json.JsonElement): String {
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return (element as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if ((obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "text") {
            return (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        }
        val kids = obj["content"] as? kotlinx.serialization.json.JsonArray ?: return ""
        return kids.joinToString("") { extract(it) }
    }
}

@Serializable
data class ChannelMessageRow(
    val id: String,
    val content: String? = null,
    val user_id: String? = null,
    val is_pinned: Boolean? = null,
    val created_at: Long? = null,
)