package dev.beacon.core.chat

import dev.beacon.core.api.dto.ChatPayload
import dev.beacon.core.data.local.MessageEntity

/**
 * Persistence surface the [ChatEngine] writes through (agent.md §10: write-through
 * Room, network actions mirror endpoints first). Interface keeps the engine testable
 * without Room on the JVM (MockWebServer fixture replay drives the real fold).
 */
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
}