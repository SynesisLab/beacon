package dev.beacon.core.chat

import dev.beacon.core.data.local.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tree-path semantics  — the thread is a parent-walk, never an array. */
class BranchPathTest {
    private fun node(id: String, parent: String?, role: String = MessageEntity.ROLE_ASSISTANT, ts: Long = 0) =
        MessageEntity(
            messageId = id,
            chatId = "c",
            parentId = parent,
            role = role,
            timestamp = ts,
        )

    @Test
    fun linearThread() {
        val nodes = listOf(node("u1", null, "user"), node("a1", "u1"), node("u2", "a1", "user"), node("a2", "u2"))
        assertEquals(listOf("u1", "a1", "u2", "a2"), activePathFrom(nodes, "a2").map { it.messageId })
    }

    @Test
    fun branchForkOnlyShowsActiveBranch() {
        // u1 → a1(, a1b) → u3 → a4: the visible path follows the CURRENT leaf only.
        val nodes = listOf(
            node("u1", null, "user", 1),
            node("a1", "u1", "assistant", 2),
            node("a1b", "u1", "assistant", 3),
            node("u3", "a1", "user", 4),
            node("a4", "u3", "assistant", 5),
        )
        assertEquals(listOf("u1", "a1", "u3", "a4"), activePathFrom(nodes, "a4").map { it.messageId })
        assertEquals(listOf("u1", "a1b"), activePathFrom(nodes, "a1b").map { it.messageId })
    }

    @Test
    fun fallsBackToNewestAssistantWhenNoPointer() {
        val nodes = listOf(
            node("u1", null, "user", 1),
            node("a1", "u1", "assistant", 2),
            node("u3", "a1", "user", 3),
        )
        // The parent-walk includes the user message leading up to the assistant.
        assertEquals(listOf("u1", "a1"), activePathFrom(nodes, null).map { it.messageId })
    }

    @Test
    fun emptyWhenNoNodes() {
        assertTrue(activePathFrom(emptyList(), null).isEmpty())
        assertTrue(activePathFrom(emptyList(), "missing").isEmpty())
    }

    @Test
    fun cycleInCorruptTreeTerminates() {
        val a = node("a", "b")
        val b = node("b", "a")
        val path = activePathFrom(listOf(a, b), "a")
        assertEquals(listOf("b", "a"), path.map { it.messageId })
    }
}