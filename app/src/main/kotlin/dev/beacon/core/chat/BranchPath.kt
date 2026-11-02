package dev.beacon.core.chat

import dev.beacon.core.data.local.MessageEntity

/**
 * Resolves the visible thread: walk from the current leaf up through parents,
 * bottom-up — the OWUI tree semantics (agent.md §6.4; §17: history is a tree).
 * Pure function; Room wiring stays in the repository. Cycle-safe (corrupt data).
 */
fun activePathFrom(
    nodes: List<MessageEntity>,
    currentMessageId: String?,
): List<MessageEntity> {
    val byId = nodes.associateBy { it.messageId }
    val visited = HashSet<String>()
    // Preferred start: explicit branch pointer; else the newest assistant node.
    var cursor = currentMessageId?.let { byId[it] }
        ?: nodes.filter { it.role == MessageEntity.ROLE_ASSISTANT }.maxByOrNull { it.timestamp ?: 0 }

    val reversed = ArrayList<MessageEntity>(nodes.size)
    while (cursor != null && visited.add(cursor.messageId)) {
        reversed.add(cursor)
        cursor = cursor.parentId?.let { byId[it] }
    }
    return reversed.asReversed()
}