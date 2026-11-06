package dev.beacon.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room projection of a server chat . Secrets/content live on messages. */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    /** epoch millis */
    val updatedAt: Long?,
    val title: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val folderId: String? = null,
    /** current branch pointer (server history.currentId; §6.4) */
    val currentMessageId: String? = null,
    val tags: List<String> = emptyList(),
    val lastReadAt: Long? = null,
)

/**
 * One node of the OWUI message tree . [partsJson] holds the normalized
 * render parts; [content] keeps the raw server content string for reconciliation
 * + M2's segmenter. [outputJson] keeps the structured output list as delivered.
 */
@Entity(
    tableName = "messages",
    indices = [Index("chatId"), Index("parentId")],
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val parentId: String?,
    /** JSON array of child message ids (tree fan-out; M3 branches) */
    val childrenIds: List<String> = emptyList(),
    val role: String,
    val content: String? = null,
    val outputJson: String? = null,
    /** stream state flag: false while receiving / interrupted */
    val done: Boolean = true,
    /** epoch millis (server updated_at when known, else device clock) */
    val timestamp: Long?,
    val meta: Map<String, String> = emptyMap(),
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}