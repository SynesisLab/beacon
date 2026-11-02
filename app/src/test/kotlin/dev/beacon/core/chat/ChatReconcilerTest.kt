package dev.beacon.core.chat

import dev.beacon.core.api.dto.StoredMessage
import dev.beacon.core.data.local.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table-driven reconciler rules (agent.md §9.2, §13). */
class ChatReconcilerTest {
    private fun local(
        id: String,
        parent: String? = null,
        role: String = "assistant",
        done: Boolean = true,
        ts: Long = 100,
        content: String = "same",
    ) = MessageEntity(
        messageId = id,
        chatId = "chat",
        parentId = parent,
        role = role,
        done = done,
        timestamp = ts,
        content = content,
    )

    private fun server(
        id: String,
        parent: String? = null,
        role: String = "assistant",
        done: Boolean? = true,
        ts: Long? = 100,
        content: String? = "same",
    ) = StoredMessage(
        id = id,
        parentId = parent,
        role = role,
        done = done,
        updatedAt = ts,
        content = content,
    )

    @Test
    fun identicalStatesYieldNoOps() {
        val result = ChatReconciler.reconcile(
            local = listOf(local("m1")),
            server = listOf(server("m1")),
            chatId = "chat",
            serverCurrentId = "m1",
            localCurrentId = "m1",
        )
        assertTrue(result.upserts.isEmpty())
        assertEquals(null, result.pointer)
    }

    @Test
    fun partialLocalPlusServerFinalAdoptsServer() {
        val result = ChatReconciler.reconcile(
            local = listOf(local("m1", done = false, content = "partial")),
            server = listOf(server("m1", content = "full final")),
            chatId = "chat",
            serverCurrentId = "m1",
            localCurrentId = "m1",
        )
        assertEquals(1, result.upserts.size)
        assertEquals("full final", result.upserts.single().content)
        assertTrue(result.upserts.single().done)
    }

    @Test
    fun newerLocalUserEditWins() {
        val result = ChatReconciler.reconcile(
            local = listOf(local("m1", role = "user", ts = 500, content = "my edited text")),
            server = listOf(server("m1", role = "user", ts = 100, content = "server text")),
            chatId = "chat",
            serverCurrentId = "m1",
            localCurrentId = "m1",
        )
        assertTrue(result.upserts.isEmpty())
    }

    @Test
    fun branchPointerFollowsServer() {
        val result = ChatReconciler.reconcile(
            local = listOf(local("m1"), local("m2", parent = "m1")),
            server = listOf(server("m1"), server("m2", parent = "m1")),
            chatId = "chat",
            serverCurrentId = "m2",
            localCurrentId = "m1",
        )
        assertEquals("m2", result.pointer)
    }

    @Test
    fun locallyMissingServerMessageUpserts() {
        val result = ChatReconciler.reconcile(
            local = emptyList(),
            server = listOf(server("m1"), server("m2", parent = "m1")),
            chatId = "chat-9",
            serverCurrentId = "m2",
            localCurrentId = null,
        )
        assertEquals(2, result.upserts.size)
        assertEquals("chat-9", result.upserts.first().chatId)
    }
}