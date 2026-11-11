package dev.beacon.core.data

import dev.beacon.core.api.ChatsApi
import dev.beacon.core.api.dto.ChatPayload
import dev.beacon.core.api.dto.ChatsListRow
import dev.beacon.core.api.dto.StoredMessage
import dev.beacon.core.chat.ChatStore
import dev.beacon.core.chat.activePathFrom
import dev.beacon.core.data.local.BeaconDatabase
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.core.data.local.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-first data access for chats : all screens read Room; network
 * actions write through. Mutations mirror OWUI endpoints BEFORE optimistic-local
 * application when the device is online (§6.4). Implements [ChatStore] for the engine.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val db: BeaconDatabase,
    private val chatsApi: ChatsApi,
    private val session: dev.beacon.core.data.SessionStore,
) : ChatStore {
    internal fun chats() = db.chats()
    internal fun messages() = db.messages()

    fun observeChats(): Flow<List<ChatEntity>> = db.chats().observeAll()

    override suspend fun serverVersion(): String? = session.settings.first().serverVersion

    // ── ChatStore (engine surface) ───────────────────────────────────────────

    override suspend fun insertNode(message: MessageEntity): Unit = withContext(Dispatchers.IO) {
        db.messages().upsert(listOf(message))
        message.parentId?.let { parentId ->
            if (parentId != message.messageId) {
                db.messages().byId(parentId)?.let { parent ->
                    if (!parent.childrenIds.contains(message.messageId)) {
                        db.messages().upsert(listOf(parent.copy(childrenIds = parent.childrenIds + message.messageId)))
                    }
                }
            }
        }
        Unit
    }

    override suspend fun setCurrentMessage(chatId: String, messageId: String) =
        withContext(Dispatchers.IO) { db.chats().setCurrentMessageId(chatId, messageId) }

    override suspend fun setChatTitle(chatId: String, title: String) =
        withContext(Dispatchers.IO) { db.chats().setTitle(chatId, title) }

    override suspend fun setChatTags(chatId: String, tags: List<String>) =
        withContext(Dispatchers.IO) { db.chats().setTags(chatId, tags) }

    override suspend fun setDone(messageId: String, done: Boolean) =
        withContext(Dispatchers.IO) { db.messages().setDone(messageId, done) }

    override suspend fun updateAssistantContent(
        chatId: String,
        messageId: String,
        content: String,
        outputJson: String?,
        done: Boolean,
        timestamp: Long,
    ): Unit = withContext(Dispatchers.IO) {
        val existing = db.messages().byId(messageId)
        if (existing != null) {
            db.messages().upsert(
                listOf(
                    existing.copy(
                        content = content,
                        outputJson = outputJson ?: existing.outputJson,
                        done = done,
                        timestamp = timestamp,
                    ),
                ),
            )
            db.chats().byId(chatId)?.let { chat ->
                db.chats().upsert(listOf(chat.copy(updatedAt = timestamp)))
            }
        }
        Unit
    }

    override suspend fun activePath(chatId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        activePathFrom(db.messages().byChat(chatId), db.chats().byId(chatId)?.currentMessageId)
    }

    override suspend fun upsertServerChat(payload: ChatPayload) = upsertPayload(payload)

    override suspend fun chatTitle(chatId: String): String? = withContext(Dispatchers.IO) {
        db.chats().byId(chatId)?.title?.takeIf { it.isNotBlank() }
    }

    override suspend fun chatTags(chatId: String): List<String> = withContext(Dispatchers.IO) {
        db.chats().byId(chatId)?.tags ?: emptyList()
    }

    override suspend fun chatCurrentMessageId(chatId: String): String? = withContext(Dispatchers.IO) {
        db.chats().byId(chatId)?.currentMessageId
    }

    override suspend fun saveServerTree(chatId: String, currentId: String, modelId: String?): Boolean =
        withContext(Dispatchers.IO) {
            val rows = db.messages().byChat(chatId)
            val path = activePathFrom(rows, currentId)
            val tree = kotlinx.serialization.json.buildJsonObject {
                put(
                    "models",
                    kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(modelId.orEmpty()))
                    },
                )
                put(
                    "history",
                    kotlinx.serialization.json.buildJsonObject {
                        put("currentId", currentId)
                        put(
                            "messages",
                            kotlinx.serialization.json.buildJsonObject {
                                rows.forEach { row ->
                                    put(row.messageId, dev.beacon.core.chat.messageNodeJson(row))
                                }
                            },
                        )
                    },
                )
                put(
                    "messages",
                    kotlinx.serialization.json.buildJsonArray {
                        path.forEach { row ->
                            add(
                                kotlinx.serialization.json.buildJsonObject {
                                    put("role", row.role)
                                    put("content", row.content.orEmpty())
                                },
                            )
                        }
                    },
                )
            }
            chatsApi.saveTree(chatId, tree)
        }

    override suspend fun messages(chatId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) { db.messages().byChat(chatId) }

    override suspend fun fetchServerTree(chatId: String): ChatPayload? =
        withContext(Dispatchers.IO) {
            try {
                chatsApi.full(chatId)
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun refreshServerChat(chatId: String): Boolean = adoptServerChat(chatId) != null

    override suspend fun createServerChat(title: String, modelId: String): ChatPayload? =
        withContext(Dispatchers.IO) {
            try {
                // The web's initChatHandler body (Chat.svelte:4829-4847): title/models
                // + an empty history; the title lives INSIDE the chat dict
                // (models/chats.py:673-675) and the server assigns the id.
                chatsApi.create(
                    dev.beacon.core.api.dto.NewChatBody(
                        chat = dev.beacon.core.api.dto.ChatStorage(
                            title = title,
                            models = listOf(modelId),
                            history = dev.beacon.core.api.dto.ChatHistory(messages = emptyMap(), currentId = null),
                            messages = emptyList(),
                            tags = emptyList(),
                            timestamp = System.currentTimeMillis(),
                        ),
                    ),
                )
            } catch (e: Exception) {
                android.util.Log.w("BeaconChat", "createServerChat failed: ${e.message}")
                null
            }
        }

    // ── server-mirroring operations ──────────────────────────────────────────

    /** Refresh [page] into Room + pinned rows on page 1 (list rows lack `pinned` — chats.py:351-359). */
    suspend fun refreshChats(page: Int = 1): Unit = withContext(Dispatchers.IO) {
        val rows = chatsApi.list(page = page)
        val pinnedRows = if (page == 1) {
            try {
                chatsApi.pinned()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val pinnedIds = pinnedRows.map { it.id }.toSet()
        // List rows carry no tags/branch pointer — merge with the locally-known
        // row instead of clobbering them (REPLACE would null them out).
        db.chats().upsert(
            (rows + pinnedRows).map { row ->
                val existing = db.chats().byId(row.id)
                row.toEntity(pinnedIds.contains(row.id)).copy(
                    tags = existing?.tags ?: emptyList(),
                    currentMessageId = existing?.currentMessageId,
                    folderId = existing?.folderId,
                )
            },
        )
    }

    /**
     * Server-side title search: GET /api/v1/chats/search?q= (chats.py:900).
     * Rows merge with locally-known state exactly like [refreshChats] (tags,
     * branch pointer, folder survive — list rows don't carry them).
     */
    suspend fun searchServer(q: String): List<ChatEntity> = withContext(Dispatchers.IO) {
        chatsApi.search(q).map { row ->
            val existing = db.chats().byId(row.id)
            row.toEntity(existing?.pinned ?: false).copy(
                tags = existing?.tags ?: emptyList(),
                currentMessageId = existing?.currentMessageId,
                folderId = existing?.folderId,
            )
        }
    }

    /** Fetch the full tree (§6.4) and adopt it. */
    suspend fun adoptServerChat(chatId: String): ChatPayload? = withContext(Dispatchers.IO) {
        val payload = try {
            chatsApi.full(chatId)
        } catch (e: Exception) {
            null
        }
        if (payload != null) upsertPayload(payload)
        payload
    }

    suspend fun upsertPayload(payload: ChatPayload) = withContext(Dispatchers.IO) {
        val storage = payload.chat
        val history = storage?.history
        // Preserve locally-known tags (list refresh merges them; full adopt re-derives).
        val existing = db.chats().byId(payload.id)

        db.chats().upsert(
            ChatEntity(
                chatId = payload.id,
                updatedAt = epochMs(payload.updated_at),
                title = payload.title,
                pinned = payload.pinned ?: existing?.pinned ?: false,
                archived = payload.archived,
                folderId = payload.folder_id ?: existing?.folderId,
                currentMessageId = history?.currentId ?: existing?.currentMessageId,
                tags = existing?.tags ?: emptyList(),
            ),
        )
        val nodes = history?.messages?.values.orEmpty()
        // §4 fork truth: the server's streaming upsert can null a node's
        // parentId (the {existing,**incoming} spread; device DB proof
        // 2026-11-03 — the fork's regen orphaned itself). A locally-known
        // parent OUTRANKS a server null; the finalize re-saves the merged
        // tree, so the web sees the corrected chain too.
        val existingRows = db.messages().byChat(payload.id).associateBy { it.messageId }
        db.messages().upsert(nodes.map { incoming ->
            val entity = incoming.toEntity(payload.id)
            if (entity.parentId == null) {
                existingRows[entity.messageId]?.parentId?.let { known -> entity.copy(parentId = known) }
                    ?: entity
            } else entity
        })
        db.chats().setCurrentMessageId(payload.id, history?.currentId ?: existing?.currentMessageId)
    }

    suspend fun chat(chatId: String): ChatEntity? =
        withContext(Dispatchers.IO) { db.chats().byId(chatId) }

    suspend fun chatsCount(): Int = withContext(Dispatchers.IO) { db.chats().count() }

    suspend fun deleteChat(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            chatsApi.delete(chatId)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            db.messages().deleteByChat(chatId)
            db.chats().delete(chatId)
        }
        ok
    }

    /** Pin toggles server-side (chats.py:1751); response truth wins, Room follows. */
    suspend fun togglePin(chatId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            upsertPayload(chatsApi.pin(chatId))
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun toggleArchive(chatId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            upsertPayload(chatsApi.archive(chatId))
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** Maps a list row + pinned ids into Room entities (server owns updated_at). */
private fun ChatsListRow.toEntity(pinned: Boolean): ChatEntity = ChatEntity(
    chatId = id,
    updatedAt = epochMs(updated_at),
    title = title,
    pinned = pinned,
    archived = archived,
    folderId = null,
    currentMessageId = null,
    tags = emptyList(),
    lastReadAt = epochMs(last_read_at),
)

/** Server timestamps are epoch SECONDS (python time.time()) — normalize to millis. */
private fun epochMs(seconds: Long?): Long? =
    seconds?.takeIf { it > 0 }?.let { if (it < 10_000_000_000L) it * 1000 else it }

/** Stored tree node → Room row (tree shape preserved: parentId + childrenIds). */
private fun StoredMessage.toEntity(chatId: String): MessageEntity = MessageEntity(
    messageId = id,
    chatId = chatId,
    parentId = parentId,
    childrenIds = childrenIds,
    role = role,
    content = content,
    outputJson = output?.toString(),
    done = done ?: true,
    timestamp = updatedAt ?: createdAt ?: timestamp,
    meta = buildMap {
        contextSummary?.takeIf { it.isNotBlank() }?.let { put("contextSummary", it) }
        followUps?.takeIf { it.isNotEmpty() }?.let {
            put("followUps", kotlinx.serialization.json.buildJsonArray { it.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }.toString())
        }
    },
)