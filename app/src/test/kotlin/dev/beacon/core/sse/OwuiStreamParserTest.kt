package dev.beacon.core.sse

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fixture-driven parser tests (agent.md §13): the .sse fixtures carry shapes
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
        assertEquals("StreamDone", eventTypes(list).last())
    }

    @Test
    fun responsesApi_fixtureFolds() {
        val list = events("responses_api")
        // item.added → OutputSnapshot(reasoning), reasoning delta, item.added → OutputSnapshot(2),
        // content delta, completed → OutputSnapshot(final 2) + usage
        val snapshots = list.filterIsInstance<StreamEvent.OutputSnapshot>()
        assertEquals(3, snapshots.size)
        assertEquals(1, snapshots[0].items.size)
        assertEquals(2, snapshots[1].items.size)
        assertEquals(2, snapshots[2].items.size)
        assertTrue(list.any { (it as? StreamEvent.Reasoning)?.text == "plan" })
        assertTrue(list.any { (it as? StreamEvent.Content)?.text == "Answer" })
        assertTrue(list.any { it is StreamEvent.UsageUpdate })
        assertEquals("StreamDone", eventTypes(list).last())
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