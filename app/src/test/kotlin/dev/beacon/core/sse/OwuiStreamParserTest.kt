package dev.beacon.core.sse

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fixture-driven parser tests : the .sse fixtures carry shapes
 * verified against the open-webui clone (docs/protocol-notes.md, M1 section).
 * Every fixture is driven through the scanner byte-by-byte as well — fold output
 * must be identical either way.
 */
class OwuiStreamParserTest {
    private val scanner = SseFrameScanner()
    private val parser = OwuiStreamParser()

    private fun fixtureFrames(name: String): List<SseFrameScanner.Frame> {
        val bytes = File("src/test/fixtures/sse/$name.sse").readBytes()
        assertEquals(('\n'.code.toByte()), bytes[bytes.size - 1]) // LF-terminated fixtures
        return scanner.feed(bytes) + scanner.flush()
    }

    private fun events(name: String): List<StreamEvent> =
        fixtureFrames(name).flatMap { parser.onFrame(it) }

    private fun eventTypes(events: List<StreamEvent>): List<String> =
        events.map { it.javaClass.simpleName }

    @Test
    fun plainDeltas_fixtureFolds() {
        val list = events("plain_deltas")
        assertEquals(
            listOf(
                "Content", "Content", "FinishReason", "UsageUpdate", "StreamDone",
            ),
            eventTypes(list),
        )
        assertEquals("Hello", (list[0] as StreamEvent.Content).text)
        assertEquals(", world!", (list[1] as StreamEvent.Content).text)
        assertEquals("stop", (list[2] as StreamEvent.FinishReason).value)
        val usage = (list[3] as StreamEvent.UsageUpdate).usage as kotlinx.serialization.json.JsonObject
        org.junit.Assert.assertEquals(
            kotlinx.serialization.json.JsonPrimitive(10),
            usage["prompt_tokens"],
        )
        // Byte-by-byte re-drive must produce identical fold output:
        parser.reset()
        assertEquals(list, parserFramesByName("plain_deltas"))
    }

    @Test
    fun reasoningKeys_fixtureFolds() {
        val list = events("reasoning_keys")
        assertEquals(
            listOf("Reasoning", "Reasoning", "Reasoning", "Content", "StreamDone"),
            eventTypes(list),
        )
        assertEquals("Thinking A", (list[0] as StreamEvent.Reasoning).text)
        assertEquals("Thinking C", (list[2] as StreamEvent.Reasoning).text)
        assertEquals("Final answer", (list[3] as StreamEvent.Content).text)
    }

    @Test
    fun envelopes_fixtureFolds() {
        val list = events("envelopes")
        val status = list.filterIsInstance<StreamEvent.Status>().single()
        assertEquals("Searching the web…", status.text)
        assertTrue(status.done == false)
        assertTrue(list.any { it is StreamEvent.SourceEmitted })
        assertTrue(list.any { it is StreamEvent.Content })
        val snapshot = list.filterIsInstance<StreamEvent.OutputSnapshot>().single()
        assertEquals(1, snapshot.items.size)
        assertEquals(
            listOf("What next?", "Show me code"),
            list.filterIsInstance<StreamEvent.FollowUps>().single().items,
        )
        assertEquals("New chat", list.filterIsInstance<StreamEvent.TitleUpdate>().single().title)
        assertEquals(
            listOf("tag-one", "tag-two"),
            list.filterIsInstance<StreamEvent.TagsUpdate>().single().tags,
        )
        // terminal:display_file (middleware.py:961; device-verified shape
        // 2026-11-06): path + optional page — the auto-open signal.
        val displays = list.filterIsInstance<StreamEvent.TerminalFileDisplay>()
        assertEquals(2, displays.size)
        assertEquals("/home/user/beacon-ui-design.md", displays[0].path)
        assertTrue(displays[0].page == null)
        assertEquals("/home/user/report.pdf", displays[1].path)
        assertTrue(displays[1].page == 2)
        // context_compaction envelope (context_compaction.py:82-151): the
        // in-progress line, then the done event carrying the summary.
        val compactions = list.filterIsInstance<StreamEvent.CompactionStatus>()
        assertEquals(2, compactions.size)
        assertEquals("Compacting context", compactions[0].description)
        assertTrue(!compactions[0].done && !compactions[0].error)
        assertTrue(compactions[1].done)
        assertEquals("Context compacted", compactions[1].description)
        assertEquals("StreamDone", eventTypes(list).last())
    }

    @Test
    fun responsesApi_fixtureFolds() {
        val list = events("responses_api")
        // Accumulator semantics (accumulator semantics): item.added → S1(1);
        // reasoning delta APPENDS into r1 → S2(1); item.added msg → S3(2);
        // text delta appends → S4(2); completed merges by id → S5(2) + usage.
        val snapshots = list.filterIsInstance<StreamEvent.OutputSnapshot>()
        assertEquals(5, snapshots.size)
        assertEquals(listOf(1, 1, 2, 2, 2), snapshots.map { it.items.size })
        // The reasoning delta landed INSIDE the reasoning item (S2), not a
        // separate text event — the thinking block now streams in place.
        assertEquals("plan", outputText(snapshots[1].items))
        // S3/S4 carry BOTH items; the message's text grows in place.
        assertEquals("Answer", outputText(snapshots[3].items.takeLast(1)))
        assertEquals("planAnswer", outputText(snapshots[4].items))
        assertTrue(list.any { it is StreamEvent.UsageUpdate })
        assertEquals("StreamDone", eventTypes(list).last())
        // Byte-by-byte re-drive must produce an identical fold:
        parser.reset()
        assertEquals(
            eventTypes(list),
            eventTypes(parserFramesByName("responses_api")),
        )
    }

    @Test
    fun errorsAndBeats_fixtureFolds() {
        val list = events("errors_and_beats")
        assertTrue(list.any { (it as? StreamEvent.Content)?.text == "Partial before failure" })
        val error = list.filterIsInstance<StreamEvent.ErrorSurface>().single()
        assertEquals("Chat completion stream failed", error.message)
        assertTrue(list.any { it is StreamEvent.StreamDone })
    }

    @Test
    fun emptyOutput_fixtureProvesPresenceGate() {
        val list = events("empty_output_authoritative")
        val snapshot = list.filterIsInstance<StreamEvent.OutputSnapshot>().single()
        assertTrue(snapshot.items.isEmpty()) // present + empty — still authoritative
        assertEquals("StreamDone", eventTypes(list).last())
    }

    @Test
    fun parseIsPureJVM_noAndroidDependencies() {
        // Guards the milestone rule that protocol surfaces stay plain-JVM testable.
        val raw = """{"type":"chat:title","data":"t"}"""
        val frame = SseFrameScanner.Frame("unknown", raw)
        assertEquals("t", parser.onFrame(frame).filterIsInstance<StreamEvent.TitleUpdate>().single().title)
    }

    private fun outputText(items: List<kotlinx.serialization.json.JsonElement>): String =
        items.joinToString("") { item ->
            val obj = item as? kotlinx.serialization.json.JsonObject
            (obj?.get("content") as? kotlinx.serialization.json.JsonArray)?.joinToString("") { part ->
                ((part as? kotlinx.serialization.json.JsonObject)?.get("text")
                    as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            } ?: ""
        }

    @Test
    fun messageErrorEvent_detailEnvelopeSurfaces() {
        // FastAPI-style detail envelope: the server's own words must surface
        // (the user diagnoses from them, not from a generic label).
        val raw = """{"type":"chat:message:error","data":{"detail":"Quota exceeded for the day"}}"""
        val surface = parser.onFrame(SseFrameScanner.Frame("unknown", raw))
            .filterIsInstance<StreamEvent.ErrorSurface>().single()
        assertEquals("Quota exceeded for the day", surface.message)
    }

    @Test
    fun messageErrorEvent_fallbackContent() {
        val raw = """{"type":"chat:message:error","data":{"content":"Backend exploded"}}"""
        val surface = parser.onFrame(SseFrameScanner.Frame("unknown", raw))
            .filterIsInstance<StreamEvent.ErrorSurface>().single()
        assertEquals("Backend exploded", surface.message)
    }

    @Test
    fun errorFrame_test() {
        val raw = """{"error":{"message":"Model 'gpt-x' not found","type":"not_found"}}"""
        val surface = parser.onFrame(SseFrameScanner.Frame("unknown", raw))
            .filterIsInstance<StreamEvent.ErrorSurface>().single()
        assertEquals("Model 'gpt-x' not found", surface.message)
    }

    private fun parserFramesByName(name: String): List<StreamEvent> {
        val bytes = File("src/test/fixtures/sse/$name.sse").readBytes()
        val scanner2 = SseFrameScanner()
        val events = ArrayList<StreamEvent>()
        bytes.forEach { byte ->
            scanner2.feed(byteArrayOf(byte)).forEach { frame ->
                events.addAll(parser.onFrame(frame))
            }
        }
        scanner2.flush().forEach { frame -> events.addAll(parser.onFrame(frame)) }
        return events
    }
}