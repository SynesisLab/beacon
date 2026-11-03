package dev.beacon.core.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task-socket event family (M4-d3): the envelopes socket `events` deliver fold
 * through the SAME parser ([StreamEvent]) as SSE. Shapes verified in the clone:
 * - deltas: {type:'response.output_text.delta', delta} (middleware.py:5467-5479);
 * - batched flush wrap: {type:'response:completion', data:{type:<delta>}}
 *   (middleware.py:4720-4733);
 * - terminal: {type:'chat:completion', data:{done:true, output, title, usage}}
 *   (middleware.py:6341-6379);
 * - tool-chip items ride output_item.added/done.
 */
class TaskSocketParserTest {
    private val parser = OwuiStreamParser()

    private fun fold(payload: String): List<StreamEvent> =
        parser.onFrame(SseFrameScanner.Frame(null, payload))

    @Test
    fun textDeltasFold() {
        val events = fold("""{"type":"response.output_text.delta","delta":"Hel"}""") +
            fold("""{"type":"response.output_text.delta","delta":"lo"}""")
        assertEquals("Content", events[0].javaClass.simpleName)
        assertEquals("Content", events[1].javaClass.simpleName)
        assertEquals("Hello", (events[0] as StreamEvent.Content).text + (events[1] as StreamEvent.Content).text)
    }

    @Test
    fun reasoningDeltaFolds() {
        val events = fold("""{"type":"response.reasoning_text.delta","delta":"why"}""")
        assertEquals(1, events.size)
        assertTrue(events[0] is StreamEvent.Reasoning)
        assertEquals("why", (events[0] as StreamEvent.Reasoning).text)
    }

    @Test
    fun batchedDeltaWrapUnfolds() {
        val events = fold(
            """{"type":"response:completion","data":{"type":"response.output_text.delta","delta":"wrapped"}}""",
        )
        assertEquals(1, events.size)
        assertTrue(events[0] is StreamEvent.Content)
        assertEquals("wrapped", (events[0] as StreamEvent.Content).text)
    }

    @Test
    fun summaryDeltaAlsoFolds() {
        val events = fold("""{"type":"response.reasoning_summary_text.delta","delta":"s"}""")
        assertTrue(events.single() is StreamEvent.Reasoning)
    }

    @Test
    fun terminalDoneFoldsSnapshotThenDone() {
        val events = fold(
            """{"type":"chat:completion","data":{"done":true,
              "output":[{"type":"message","status":"completed","role":"assistant",
                         "content":[{"type":"output_text","text":"Final"}]}],
              "title":"New title","usage":{"prompt_tokens":3}}}""",
        )
        val types = events.map { it.javaClass.simpleName }
        // snapshot → usage → title → done (parse order per the parser's branch)
        assertEquals(listOf("OutputSnapshot", "UsageUpdate", "TitleUpdate", "StreamDone"), types)
        val snap = (events[0] as StreamEvent.OutputSnapshot).items
        assertEquals("Final", outputText(snap))
    }

    @Test
    fun toolChipItemsSnapshotThroughOutputItem() {
        val events = fold(
            """{"type":"response.output_item.done","output_index":0,
             "item":{"type":"function_call","name":"write_file","call_id":"c1","arguments":"{}","status":"completed"}}""",
        )
        assertEquals(1, events.size)
        val snap = events[0] as StreamEvent.OutputSnapshot
        val single = snap.items.single() as kotlinx.serialization.json.JsonObject
        assertEquals("write_file", (single["name"] as JsonPrimitive).content)
    }

    @Test
    fun errorTaskEventsSurface() {
        val events = fold("""{"type":"chat:completion","data":{"error":{"message":"boom"}}}""")
        assertEquals(listOf("ErrorSurface"), events.map { it.javaClass.simpleName })
        assertEquals("boom", (events[0] as StreamEvent.ErrorSurface).message)
    }

    @Test
    fun envelopeRoundTripParses() {
        val envelope = buildJsonObject {
            put("chat_id", JsonPrimitive("chat-1"))
            put("message_id", JsonPrimitive("msg-1"))
            put("data", Json.parseToJsonElement("""{"type":"response.output_text.delta","delta":"Hi"}"""))
        }
        // The engine feeds ONLY the data payload into the parser:
        val data = (envelope["data"] as kotlinx.serialization.json.JsonObject).toString()
        val events = parser.onFrame(SseFrameScanner.Frame(null, data))
        assertEquals(1, events.size)
        assertTrue(events[0] is StreamEvent.Content)
        assertEquals("Hi", (events[0] as StreamEvent.Content).text)
    }

    private fun outputText(items: List<kotlinx.serialization.json.JsonElement>): String =
        items.joinToString("") { item ->
            val obj = item as? kotlinx.serialization.json.JsonObject
            (obj?.get("content") as? kotlinx.serialization.json.JsonArray)?.joinToString("") { part ->
                ((part as? kotlinx.serialization.json.JsonObject)?.get("text") as? JsonPrimitive)?.content ?: ""
            } ?: ""
        }
}