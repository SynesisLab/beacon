package dev.beacon.core.chat

import dev.beacon.core.api.dto.ChatPayload
import dev.beacon.core.data.local.MessageEntity
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * Persistence surface the [ChatEngine] writes through (agent.md §10: write-through
 * Room, network actions mirror endpoints first). Interface keeps the engine testable
 * without Room on the JVM (MockWebServer fixture replay drives the real fold).
 */

/** The chat-tree JSON node shape the server stores (models/chats.py ChatMessage). */
fun messageNodeJson(row: dev.beacon.core.data.local.MessageEntity): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("id", row.messageId)
        row.parentId?.let { put("parentId", it) }
        put(
            "childrenIds",
            kotlinx.serialization.json.buildJsonArray {
                row.childrenIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            },
        )
        put("role", row.role)
        put("content", row.content.orEmpty())
        row.timestamp?.let { put("timestamp", it / 1000) } // epoch SECONDS on this wire (§17)
        put("done", row.done)
    }

interface ChatStore {
    suspend fun insertNode(message: MessageEntity)
    suspend fun setCurrentMessage(chatId: String, messageId: String)
    suspend fun setChatTitle(chatId: String, title: String)
    suspend fun setChatTags(chatId: String, tags: List<String>)
    suspend fun setDone(messageId: String, done: Boolean)
    suspend fun updateAssistantContent(
        chatId: String,
        messageId: String,
        content: String,
        outputJson: String?,
        done: Boolean,
        timestamp: Long,
    )
    suspend fun activePath(chatId: String): List<MessageEntity>

    /** The ROOM row's title (the server-set title rides chat:title events) —
     *  the room-first paint's topbar source. */
    suspend fun chatTitle(chatId: String): String?

    /** All nodes of the chat (tree, not just the active path). */
    suspend fun messages(chatId: String): List<MessageEntity>

    /** Adopt an externally-obtained server payload into the store. */
    suspend fun upsertServerChat(payload: ChatPayload)

    /** Raw fetch of the server tree (reconciler input), or null when unreachable. */
    suspend fun fetchServerTree(chatId: String): ChatPayload?

    /** Fetch the full tree from the instance + upsert; false when unreachable. */
    suspend fun refreshServerChat(chatId: String): Boolean

    /**
     * POST /api/v1/chats/new — the client creates the chat BEFORE the first
     * completion on current servers (the web's initChatHandler,
     * Chat.svelte:4817-4847): with no real chat_id the server treats the turn
     * as a TEMP chat (nothing saved, no events). Returns the created payload
     * (its id is the server-assigned chat id) or null on failure.
     */
    suspend fun createServerChat(title: String, modelId: String): ChatPayload?

    /** The recorded server version ("0.12.5") — inline-era decisions. */
    suspend fun serverVersion(): String?

    /**
     * §4 version-tree: POST /{id} the FULL chat tree from Room (the chat dict
     * the web's saveChatHandler sends per turn — Chat.svelte:4877-4890) with
     * [currentId] = the leaf. The fork's sibling must reach the server this
     * way: the server's streaming upsert merges into the EXISTING node
     * (models/chats.py:1134-1159), never re-parents it.
     */
    suspend fun saveServerTree(chatId: String, currentId: String, modelId: String?): Boolean

    /** A branch switch: set the active leaf via [currentId] and repaint-able. */
    suspend fun chatCurrentMessageId(chatId: String): String?
}