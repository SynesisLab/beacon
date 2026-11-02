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
}