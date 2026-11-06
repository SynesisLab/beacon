package dev.beacon.core.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    /** Cold start = Room-first paint ; page cursor via LIMIT offset. */
    @Query(
        """
        SELECT * FROM chats
        WHERE archived = :archived
        ORDER BY pinned DESC, COALESCE(updatedAt, 0) DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun page(archived: Boolean = false, limit: Int, offset: Int): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE archived = 0 ORDER BY pinned DESC, COALESCE(updatedAt, 0) DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun count(): Int

    @Query("SELECT * FROM chats WHERE chatId = :chatId")
    suspend fun byId(chatId: String): ChatEntity?

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun delete(chatId: String)

    @Upsert
    suspend fun upsert(chats: List<ChatEntity>)

    @Upsert
    suspend fun upsert(chat: ChatEntity)

    @Query("UPDATE chats SET pinned = :pinned WHERE chatId = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("UPDATE chats SET archived = :archived WHERE chatId = :chatId")
    suspend fun setArchived(chatId: String, archived: Boolean)

    @Query("UPDATE chats SET title = :title WHERE chatId = :chatId")
    suspend fun setTitle(chatId: String, title: String)

    @Query("UPDATE chats SET currentMessageId = :messageId WHERE chatId = :chatId")
    suspend fun setCurrentMessageId(chatId: String, messageId: String?)

    @Query("UPDATE chats SET tags = :tags WHERE chatId = :chatId")
    suspend fun setTags(chatId: String, tags: List<String>)
}

@Dao
interface MessageDao {
    @Upsert
    suspend fun upsert(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE chatId = :chatId")
    suspend fun byChat(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun byId(messageId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: String)

    @Query("UPDATE messages SET done = :done WHERE messageId = :messageId")
    suspend fun setDone(messageId: String, done: Boolean)
}