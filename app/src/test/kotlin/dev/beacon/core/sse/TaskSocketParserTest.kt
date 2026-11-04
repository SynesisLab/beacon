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
        // Accumulator semantics (Conduit parity): deltas APPEND into the item
        // and every mutation emits a Snapshot — one truth list, the live paint.
        val events = fold("""{"type":"response.output_text.delta","delta":"Hel"}""") +
            fold("""{"type":"response.output_text.delta","delta":"lo"}""")
        val snaps = events.filterIsInstance<StreamEvent.OutputSnapshot>()
        assertEquals(2, snaps.size)
        assertEquals("Hel", outputText(snaps[0].items))
        assertEquals("Hello", outputText(snaps[1].items))
    }

    @Test
    fun reasoningDeltaFolds() {
        val events = fold("""{"type":"response.reasoning_text.delta","delta":"why"}""")
        val snap = events.single() as StreamEvent.OutputSnapshot
        val item = snap.items.single() as kotlinx.serialization.json.JsonObject
        assertEquals("reasoning", (item["type"] as JsonPrimitive).content)
        assertEquals("why", outputText(snap.items))
    }

    @Test
    fun batchedDeltaWrapUnfolds() {
        val events = fold(
            """{"type":"response:completion","data":{"type":"response.output_text.delta","delta":"wrapped"}}""",
        )
        val snap = events.single() as StreamEvent.OutputSnapshot
        assertEquals("wrapped", outputText(snap.items))
    }

    @Test
    fun summaryDeltaAlsoFolds() {
        val events = fold("""{"type":"response.reasoning_summary_text.delta","delta":"s"}""")
        val snap = events.single() as StreamEvent.OutputSnapshot
        assertEquals("s", outputText(snap.items))
    }

    @Test
    fun toolRoundPreservesReasoningAndCalls() {
        // The device bug (2026-11-03): the thinking block vanished between tool
        // calls and the tool result replaced its call. With the accumulator +
        // merge: round-1 reasoning survives, the function_call survives its
        // own function_call_output (call_id+type match; Conduit issue #751).
        parser.reset()
        fold("""{"type":"response.output_item.added","output_index":0,
                "item":{"type":"reasoning","id":"r1","status":"in_progress","content":[]}}""")
        fold("""{"type":"response.reasoning_text.delta","item_id":"r1","delta":"thinking…"}""")
        fold("""{"type":"response.output_item.added","output_index":1,
                "item":{"type":"function_call","id":"fc1","call_id":"c1","name":"generate_image","arguments":"{}","status":"in_progress"}}""")
        // The tool result arrives as its own item, sharing call_id c1:
        val afterResult = fold(
            """{"type":"chat:completion","data":{"output":[
                 {"type":"reasoning","id":"r1","status":"completed","content":[{"type":"output_text","text":"thinking…"}]},
                 {"type":"function_call","id":"fc1","call_id":"c1","name":"generate_image","arguments":"{}","status":"completed"},
                 {"type":"function_call_output","call_id":"c1","output":[{"type":"input_text","text":"done"}]}
               ]}}""",
        ).filterIsInstance<StreamEvent.OutputSnapshot>().single()
        val types = afterResult.items.map { (it as kotlinx.serialization.json.JsonObject)["type"].toString() }
        assertTrue("reasoning survives the tool round: $types", types.any { it.contains("reasoning") })
        assertTrue("the call survives its result: $types", types.any { it.contains("function_call") })
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
        val snap = events.single() as StreamEvent.OutputSnapshot
        assertEquals("Hi", outputText(snap.items))
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

    private fun outputText(items: List<kotlinx.serialization.json.JsonElement>): String =
        items.joinToString("") { item ->
            val obj = item as? kotlinx.serialization.json.JsonObject
            (obj?.get("content") as? kotlinx.serialization.json.JsonArray)?.joinToString("") { part ->
                ((part as? kotlinx.serialization.json.JsonObject)?.get("text") as? JsonPrimitive)?.content ?: ""
            } ?: ""
        }
}