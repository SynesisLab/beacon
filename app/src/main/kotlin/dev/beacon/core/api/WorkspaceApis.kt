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
// folders.py:92 FolderNameIdResponse {id, user_id, name, parent_id?, …}
// notes.py:113 NoteItemResponse {id, title, data?, is_pinned?, updated_at, …}
// channels.py:191 ChannelModel {id, name, description?, is_private?, …}
// files.py:485 FileListResponse {items: [FileModelResponse{id, filename, …}], total}
// prompts.py PromptModel {id?, command, name, content}
// tools.py ToolModel {id, name, …}
// knowledge.py KnowledgeModel {id, name, …}

@Serializable
data class FolderRow(val id: String, val name: String, val parent_id: String? = null, val unread_count: Int = 0)

@Serializable
data class NoteRow(val id: String, val title: String, val is_pinned: Boolean? = null, val updated_at: Long? = null)

/** memories.py:31-42 MemoryModel (user_id/meta unused by the surface). */
@Serializable
data class MemoryRow(
    val id: String,
    val content: String,
    val type: String = "context",
    val path: String? = null,
    val updated_at: Long? = null,
    val created_at: Long? = null,
)

@Serializable
data class ChannelRow(val id: String, val name: String, val description: String? = null, val is_private: Boolean? = null)

@Serializable
data class FileRow(val id: String, val filename: String, val created_at: Long? = null, val meta: FileMetaDto? = null)

/** files.py:52-57 FileMeta {name, content_type, size}. */
@Serializable
data class FileMetaDto(val content_type: String? = null, val size: Long? = null)

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

    /** POST /api/v1/notes/create {title, data{content{json,html,md}}} (notes.py:237).
     *  NOTE: NoteForm REJECTS `pages` in data (models/notes.py:474) — create with
     *  a legacy `content` block; the server normalizes it into the main page. */
    suspend fun createNote(title: String, plainContent: String): String? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("title", title)
            put("data", buildJsonObject { put("content", NoteDoc.contentBlock(plainContent)) })
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

    /** POST /api/v1/notes/{id}/update (notes.py:529). The server MERGES incoming
     *  `content` into the MAIN page (models/notes.py:748 rejects `pages` in data;
     *  :761-768 routes content through update_page_content → subpages survive).
     *  md+html must be STRINGS (models/notes.py:357-362); json rides along (:363). */
    suspend fun updateNote(noteId: String, title: String?, plainContent: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                put("data", buildJsonObject { put("content", NoteDoc.contentBlock(plainContent)) })
            }
            client.newCall(
                Request.Builder().url(joinUrl(requireInstance(), "/api/v1/notes/$noteId/update"))
                    .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body)
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build(),
            ).await().use { it.isSuccessful }
        }

    /** GET /api/v1/notes/{id} — the full note; reads MAIN page markdown with a
     *  legacy-content fallback (title, plain text). */
    suspend fun note(noteId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/notes/$noteId")).await().use { response ->
            if (!response.isSuccessful) return@withContext null
            val text = response.body?.string() ?: return@withContext null
            val obj = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                ?: return@withContext null
            val title = (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val content = NoteDoc.dataText(obj["data"])
            title to content
        }
    }

    /** DELETE /api/v1/notes/{id}/delete → bool (notes.py:718; write access :731). */
    suspend fun deleteNote(noteId: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(requireInstance(), "/api/v1/notes/$noteId/delete"))
                .delete().build(),
        ).await().use { it.isSuccessful }
    }

    /** POST /api/v1/notes/{id}/pin — a TOGGLE; returns the full note with is_pinned
     *  recomputed for this user (notes.py:675, toggle_note_pinned_by_id :700). */
    suspend fun pinNote(noteId: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(requireInstance(), "/api/v1/notes/$noteId/pin"))
                .post(ByteArray(0).toRequestBody(null))
                .build(),
        ).await().use { it.isSuccessful }
    }

    // ── memories (routers/memories.py; gate memories.enable → 404 when off;
    //    timestamps are epoch SECONDS, models/memories.py:61) ──

    private fun postJson(path: String, body: JsonObject) = Request.Builder()
        .url(joinUrl(requireInstance(), path))
        .post(json.encodeToString(JsonObject.serializer(), body)
            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
        .build()

    /** GET /api/v1/memories/ → list[MemoryModel] (memories.py:55-63). */
    suspend fun memories(): List<MemoryRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/memories/")).await()
            .decodeJson(ListSerializer(MemoryRow.serializer()), json)
    }

    /** POST /add {content, type:user|context, path?} → MemoryModel
     *  (AddMemoryForm memories.py:71-74; route :177-199). */
    suspend fun addMemory(content: String, type: String = "context"): Boolean =
        withContext(Dispatchers.IO) {
            client.newCall(postJson("/api/v1/memories/add", buildJsonObject {
                put("content", content)
                put("type", type)
            })).await().use { it.isSuccessful }
        }

    /** POST /{id}/update {content?, type?, path?} → MemoryModel (memories.py:546-571). */
    suspend fun updateMemory(id: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            client.newCall(postJson("/api/v1/memories/$id/update", buildJsonObject {
                put("content", content)
            })).await().use { it.isSuccessful }
        }

    /** DELETE /{id} → bool (memories.py:610-631). */
    suspend fun deleteMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(requireInstance(), "/api/v1/memories/$id"))
                .delete().build(),
        ).await().use { it.isSuccessful }
    }

    /** DELETE /delete/user → bool — wipes EVERY memory of the user
     *  (memories.py:514-538). */
    suspend fun deleteAllMemories(): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(joinUrl(requireInstance(), "/api/v1/memories/delete/user"))
                .delete().build(),
        ).await().use { it.isSuccessful }
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

/**
 * Open WebUI notes store content under `data.pages[].content.{json,html,md}`
 * (models/notes.py:156-220; legacy notes keep `data.content`). `md` is the
 * markdown source of truth for a plain-text editor; `json` is the TipTap doc
 * the web editor hydrates from; `html` is the derived render. Beacons writes
 * all three (update_page_content requires md+html as strings, notes.py:357-362).
 */
object NoteDoc {
    const val EMPTY = ""

    /** The {json, html, md} content block for a plain-text edit. */
    fun contentBlock(plain: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("json", doc(plain))
        put("html", htmlOf(plain))
        put("md", kotlinx.serialization.json.JsonPrimitive(plain))
    }

    fun htmlOf(plain: String): String {
        val paragraphs = plain.replace("\r\n", "\n").split("\n\n")
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("") }
        return paragraphs.joinToString("") { para ->
            val escaped = para.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "<p>${escaped.replace("\n", "<br>")}</p>"
        }
    }

    /** Builds a single-paragraph/plain-text TipTap doc (the minimal valid shape). */
    fun doc(plain: String): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", "doc")
        put("content", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
            put("type", "paragraph")
            put("content", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", plain)
            })))
        })))
    }

    /** Whole-data reader: pages[..main..] → legacy content → legacy {json}. */
    fun dataText(element: kotlinx.serialization.json.JsonElement?): String {
        val data = element as? kotlinx.serialization.json.JsonObject ?: return EMPTY
        val pages = data["pages"] as? kotlinx.serialization.json.JsonArray
        if (pages != null) {
            val main = pages.firstOrNull { page ->
                (page as? kotlinx.serialization.json.JsonObject)
                    ?.get("id")?.let { p -> (p as? kotlinx.serialization.json.JsonPrimitive)?.content } == "main"
            } ?: pages.firstOrNull()
            val block = (main as? kotlinx.serialization.json.JsonObject)?.get("content")
                as? kotlinx.serialization.json.JsonObject
            if (block != null) return blockText(block)
        }
        val content = data["content"] as? kotlinx.serialization.json.JsonObject
        if (content != null) return blockText(content)
        return plainText(data)
    }

    /** Reads one content block: markdown first, a TipTap-walk fallback. */
    fun blockText(block: kotlinx.serialization.json.JsonObject): String {
        val md = (block["md"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (md.isNotBlank()) return md
        val json = block["json"] as? kotlinx.serialization.json.JsonObject ?: return ""
        return extract(json)
    }

    /** Legacy whole-data reader (notes whose data IS a TipTap doc). */
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