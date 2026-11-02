package dev.beacon.core.api

import dev.beacon.core.network.await
import dev.beacon.core.network.decodeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
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
data class PromptRow(val id: String? = null, val command: String, val name: String)

@Serializable
data class ToolRow(val id: String, val name: String)

@Serializable
data class KnowledgeRow(val id: String, val name: String, val description: String? = null)

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
        client.newCall(get("/api/v1/files/")).await().decodeJson(FilesPage.serializer(), json).items
    }

    suspend fun prompts(): List<PromptRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/prompts/")).await().decodeJson(ListSerializer(PromptRow.serializer()), json)
    }

    suspend fun tools(): List<ToolRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/tools/")).await().decodeJson(ListSerializer(ToolRow.serializer()), json)
    }

    suspend fun knowledge(): List<KnowledgeRow> = withContext(Dispatchers.IO) {
        client.newCall(get("/api/v1/knowledge/")).await().decodeJson(ListSerializer(KnowledgeRow.serializer()), json)
    }

    private fun requireInstance(): String =
        source.instanceUrl() ?: throw OwuiApiException(0, null, "No connected instance")
}