package dev.beacon.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportClassifierTest {
    @Test
    fun eventStreamClassifiesHttpStream() {
        assertEquals(TransportKind.HttpStream, classifyTransport("text/event-stream; charset=utf-8"))
    }

    @Test
    fun ndjsonAlsoCarriesFrames() {
        // middleware.py:6545-6549 — x-ndjson is the same data:-frame protocol
        assertEquals(TransportKind.HttpStream, classifyTransport("application/x-ndjson"))
    }

    @Test
    fun plainJsonWithoutTaskIdIsJsonCompletion() {
        assertEquals(TransportKind.JsonCompletion, classifyTransport("application/json"))
        assertEquals(TransportKind.JsonCompletion, classifyTransport(null))
    }

    @Test
    fun taskIdJsonBodyIsTaskSocket() {
        assertEquals(
            TransportKind.TaskSocket,
            classifyTransport("application/json", bodyIsJsonObjectWithTaskId = true),
        )
    }

    @Test
    fun explicitBodyFlagWinsWhenFalse() {
        // Content-Type application/json + no task_id → jsonCompletion (not taskSocket)
        assertEquals(
            TransportKind.JsonCompletion,
            classifyTransport("application/json", bodyIsJsonObjectWithTaskId = false),
        )
    }
}

class CompletionRequestEncodeTest {
    @Test
    fun freshChatRequestOmitsChatIdAndSessionId() {
        val encoded = CompletionRequests.encode(
            CompletionRequest(
                model = "m1",
                messages = listOf(ProjectionMessage.user("hi")),
                id = "assistant-1",
                parent_id = null,
                // v0.12 user turn dict (id/parentId required; chats.py:1321 409s bare turns)
                user_message = kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive("user-1"))
                    put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                    put("content", kotlinx.serialization.json.JsonPrimitive("hi"))
                },
                background_tasks = BackgroundTasks(title_generation = true, tags_generation = true),
            ),
        )
        val obj = Json.parseToJsonElement(encoded) as kotlinx.serialization.json.JsonObject
        assertTrue(encoded.contains("\"stream\":true"))
        assertFalse(encoded.contains("chat_id")) // null omitted (explicitNulls=false)
        assertFalse(encoded.contains("session_id"))
        assertEquals("m1", (obj["model"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(
            encoded.contains("\"background_tasks\":{\"title_generation\":true,\"tags_generation\":true,\"follow_up_generation\":false}"),
        )
    }

    @Test
    fun followUpCarriesChatAndParentIds() {
        val encoded = CompletionRequests.encode(
            CompletionRequest(
                model = "m1",
                messages = listOf(
                    ProjectionMessage.user("hi"),
                    ProjectionMessage.assistant("hello"),
                ),
                chat_id = "chat-9",
                id = "assistant-2",
                parent_id = "assistant-1",
                background_tasks = BackgroundTasks(),
            ),
        )
        assertTrue(encoded.contains("\"chat_id\":\"chat-9\""))
        assertTrue(encoded.contains("\"parent_id\":\"assistant-1\""))
        assertTrue(encoded.contains("\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"hello\"}]"))
    }

    @Test
    fun reasoningEffortRidesParams() {
        // params.reasoning_effort — wire-verified (Chat.svelte:4420-4495;
        // docs/interface-mapping.md "reasoning effort = request params.reasoning_effort").
        val withEffort = CompletionRequests.encode(
            CompletionRequest(
                model = "m1",
                messages = listOf(ProjectionMessage.user("hi")),
                id = "a1",
                params = Params(reasoning_effort = "high"),
            ),
        )
        assertTrue(withEffort.contains("\"params\":{\"reasoning_effort\":\"high\"}"))
        // default (null) → params omitted entirely
        val without = CompletionRequests.encode(
            CompletionRequest(model = "m1", messages = listOf(ProjectionMessage.user("hi")), id = "a1"),
        )
        assertFalse(without.contains("\"params\""))
    }
}