package dev.beacon.core.chat

import dev.beacon.core.data.local.MessageEntity

/**
 * Idempotent reconciliation between server state and local rows .
 * Runs on resume/foreground, flick-to-refetch, post-stream crash recovery, and
 * (M4) socket reconnect.
 *
 * Rules:
 * - server message ≠ local → **server-wins**, unless the local row is a
 *   *newer user edit* (editStamp later than the server's, per the edit-timestamp
 *   guard M3 adds);
 * - assistant partial (done=false locally) + server final (done) → adopt server;
 * - branch pointer → follow the server's currentId unless the user explicitly
 *   browsed branches locally (M3 guard — until then, server-wins);
 * - pure diff: ids present locally-but-absent server-side stay (deletion is
 *   an explicit user action, never a reconcile side effect).
 */
object ChatReconciler {

    data class Diffs(
        val upserts: List<MessageEntity>,
        val pointer: String?,
    )

    fun reconcile(
        local: List<MessageEntity>,
        server: List<dev.beacon.core.api.dto.StoredMessage>,
        chatId: String,
        serverCurrentId: String?,
        localCurrentId: String?,
    ): Diffs {
        val localById = local.associateBy { it.messageId }
        val upserts = ArrayList<MessageEntity>()

        server.forEach { stored ->
            val localRow = localById[stored.id]
            val incoming = stored.toEntity(chatId).let { entity ->
                // §4 fork truth: the server's streaming upsert can null a node's
                // parentId (the {existing,**incoming} spread; models/chats.py:1134).
                // A locally-known parent OUTRANKS a server null — same guard as
                // ChatRepository.upsertPayload. Without it a regenerated fork
                // orphans on sync and activePathFrom collapses to the leaf,
                // wiping every earlier message from the visible thread
                // (user report: regenerate wiped all previous messages).
                if (entity.parentId == null) {
                    localRow?.parentId?.let { known -> entity.copy(parentId = known) } ?: entity
                } else {
                    entity
                }
            }
            when {
                localRow == null -> upserts.add(incoming)

                // Assistant partial + server final → adopt server (resurrection
                // of a killed stream's truth).
                !localRow.done && incoming.done -> upserts.add(incoming)

                // Newer local edit guard (edit timestamps M3): skip.
                (localRow.timestamp ?: 0) > (stored.updatedAt ?: stored.createdAt ?: stored.timestamp ?: 0) &&
                    localRow.role == "user" -> Unit

                localRow.content != incoming.content ||
                    localRow.outputJson != incoming.outputJson ||
                    localRow.done != incoming.done -> upserts.add(incoming)

                else -> Unit // equal: no-op (idempotent)
            }
        }

        return Diffs(
            upserts = upserts,
            pointer = when {
                serverCurrentId == null -> null
                serverCurrentId == localCurrentId -> null
                else -> serverCurrentId
            },
        )
    }

    private fun dev.beacon.core.api.dto.StoredMessage.toEntity(chatId: String): MessageEntity = MessageEntity(
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
                put("followUps", kotlinx.serialization.json.buildJsonArray { it.forEach { item -> add(kotlinx.serialization.json.JsonPrimitive(item)) } }.toString())
            }
        },
    )
}