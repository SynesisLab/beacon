package dev.beacon.core.api.dto

import kotlinx.serialization.Serializable

/**
 * Chat-surface DTOs. Field names verified in docs/protocol-notes.md (M1 · chats table);
 * decoded tolerantly — web storage is a free-form dict, the app reads a known subset.
 */

/** GET /api/v1/chats/ (list rows) — chats.py ChatTitleIdResponse (:351-359). Note: no pinned. */
@Serializable
data class ChatsListRow(
    val id: String,
    val title: String,
    val updated_at: Long? = null,
    val created_at: Long? = null,
    val snippet: String? = null,
    val active: Boolean = false,
    val archived: Boolean = false,
    val last_read_at: Long? = null,
)

/** GET /api/v1/chats/{id} — chats.py:1380 → ChatResponse (:326-338); chat dict carries the tree. */
@Serializable
data class ChatPayload(
    val id: String,
    val user_id: String? = null,
    val title: String,
    val chat: ChatStorage? = null,
    val updated_at: Long? = null,
    val created_at: Long? = null,
    val archived: Boolean = false,
    val pinned: Boolean? = null,
    val meta: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val folder_id: String? = null,
    /** GET /{id} also carries the context meter (chats.py:1404 →
     *  utils/context_compaction.py:300-306: {tokens, threshold, percent,
     *  source:'estimated'}). */
    val context_usage: ContextUsageDto? = null,
)

/** `get_chat_context_usage`'s shape (utils/context_compaction.py:300-306). */
@Serializable
data class ContextUsageDto(
    val tokens: Long? = null,
    val estimated_tokens: Long? = null,
    val threshold: Long? = null,
    val percent: Int? = null,
    val source: String? = null,
)

/** The `chat` dict: history {messages: id → msg, currentId} (models/chats.py:418,497,591). */
@Serializable
data class ChatStorage(
    /** The web's createNewChat dict (Chat.svelte:4829-4847): the server reads
     *  the title from chat['title'] (models/chats.py:673-675). */
    val title: String? = null,
    val models: List<String> = emptyList(),
    val history: ChatHistory? = null,
    val messages: List<Map<String, kotlinx.serialization.json.JsonElement>> = emptyList(),
    val tags: List<String> = emptyList(),
    val files: kotlinx.serialization.json.JsonElement? = null,
    val timestamp: Long? = null,
)

@Serializable
data class ChatHistory(
    val messages: Map<String, StoredMessage> = emptyMap(),
    val currentId: String? = null,
)

/**
 * One stored tree node (models/chats.py storage shape; web writes {id, parentId,
 * childrenIds, role, content, done, files, timestamps, meta…}). Tolerant subset.
 */
@Serializable
data class StoredMessage(
    val id: String,
    val parentId: String? = null,
    val childrenIds: List<String> = emptyList(),
    val role: String = "assistant",
    val content: String? = null,
    val done: Boolean? = null,
    val timestamp: Long? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val model: String? = null,
    val modelName: String? = null,
    /** files / usage / usage / info / annotations / etc. — carried for M2 render + M1 merge */
    val files: kotlinx.serialization.json.JsonElement? = null,
    val usage: kotlinx.serialization.json.JsonElement? = null,
    val annotations: kotlinx.serialization.json.JsonElement? = null,
    val statusHistory: kotlinx.serialization.json.JsonElement? = null,
    val output: kotlinx.serialization.json.JsonElement? = null,
    /** Native context-compaction checkpoint (context_compaction.py:127-138 upserts
     *  it onto the checkpoint message; web renders ContextCompactionLabel). */
    val contextSummary: String? = null,
    /** Stored follow-up prompts (middleware.py:3646 writes followUps onto the
     *  message; the web renders them under the last assistant turn). */
    val followUps: List<String>? = null,
)

/** POST /api/v1/chats/new — ChatForm {chat, variables?, folder_id?} (chats.py:303-306). */
@Serializable
data class NewChatBody(
    val chat: ChatStorage,
    val variables: Map<String, String> = emptyMap(),
    val folder_id: String? = null,
)