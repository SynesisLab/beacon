package dev.beacon.core.sse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level torture tests : every fixture is replayed
 * full-chunk AND byte-by-byte AND with adversarial chunk splits — results must be
 * identical. Frames may split mid-CRLF and mid-UTF-8 .
 */
class SseFrameScannerTest {
    private fun frames(vararg chunks: ByteArray): List<SseFrameScanner.Frame> {
        val scanner = SseFrameScanner()
        val result = ArrayList<SseFrameScanner.Frame>()
        chunks.forEach { result.addAll(scanner.feed(it)) }
        result.addAll(scanner.flush())
        return result
    }

    private val plain = "data: {\"a\":1}\n\ndata: {\"b\":2}\n\n".encodeToByteArray()

    @Test
    fun parsesConsecutiveFrames() {
        assertEquals(
            listOf(
                SseFrameScanner.Frame(null, "{\"a\":1}"),
                SseFrameScanner.Frame(null, "{\"b\":2}"),
            ),
            frames(plain),
        )
    }

    @Test
    fun byteByByteEqualsBulk() {
        assertEquals(frames(plain), frames(*plain.map { byteArrayOf(it) }.toTypedArray()))
    }

    @Test
    fun crlfSplitAcrossChunksResolves() {
        val splitA = "data: {\"a\":1}\n".encodeToByteArray()
        val blankAndRest = "\n\ndata: {\"b\":2}\n\n".encodeToByteArray()
        assertEquals(frames(plain), frames(splitA, blankAndRest))
        // CR variant: the CR held at end-of-chunk resolves as CRLF with the next chunk's LF;
        // the continuation keeps an explicit blank line so neither event is ambiguous.
        assertEquals(frames(plain), frames("data: {\"a\":1}\r".encodeToByteArray(), blankAndRest))
    }

    @Test
    fun loneCrIsLineSeparator() {
        val result = frames("data: {\"a\":1}\r\rdata: {\"b\":2}\r\r".encodeToByteArray())
        assertEquals(2, result.size)
        assertEquals("{\"a\":1}", result[0].data)
        assertEquals("{\"b\":2}", result[1].data)
    }

    @Test
    fun trailingCrHoldsToResolveCrlf() {
        val result = frames("data: {\"a\":1}\r\n\r".encodeToByteArray(), "\ndata: {\"b\":2}\n\n".encodeToByteArray())
        assertEquals(2, result.size)
    }

    @Test
    fun heartbeatsHaveNullData() {
        val result = frames("data: \n\n\n\n".encodeToByteArray())
        assertEquals(1, result.size)
        assertTrue(result[0].isHeartbeat)
        assertNull(result[0].data)
    }

    @Test
    fun leadingBlankLinesAreIgnored() {
        // middleware.py:6527 emits "\n\ndata: {error…}\n\n" — leading blank lines
        // must not produce phantom events.
        val result = frames("\n\ndata: {\"e\":1}\n\n".encodeToByteArray())
        assertEquals(listOf(SseFrameScanner.Frame(null, "{\"e\":1}")), result)
    }

    @Test
    fun doneSentinelIsAFrame() {
        val result = frames("data: [DONE]\n\n".encodeToByteArray())
        assertEquals(listOf(SseFrameScanner.Frame(null, "[DONE]")), result)
    }

    @Test
    fun multiLineDataJoinsWithNl() {
        val result = frames("data: a\ndata: b\n\n".encodeToByteArray())
        assertEquals(listOf(SseFrameScanner.Frame(null, "a\nb")), result)
    }

    @Test
    fun eventNamesAndCommentsBehave() {
        val input = ": keepalive\nevent: status\ndata: {\"x\":1}\n\n".encodeToByteArray()
        val result = frames(input)
        assertEquals(listOf(SseFrameScanner.Frame("status", "{\"x\":1}")), result)
    }

    @Test
    fun unterminatedFinalEventFlushes() {
        val scanner = SseFrameScanner()
        val mid = scanner.feed("data: {\"tail\":true}\n".encodeToByteArray())
        assertTrue(mid.isEmpty())
        val rest = scanner.flush()
        assertEquals(listOf(SseFrameScanner.Frame(null, "{\"tail\":true}")), rest)
    }

    @Test
    fun utf8SplitsAcrossChunksSurvive() {
        val emoji = "🛟"
        val body = "data: {\"c\":\"$emoji\"}\n\n".encodeToByteArray()
        // Cut inside the 4-byte UTF-8 sequence of the emoji:
        val cut = body.indexOfFirst { (it.toInt() and 0xFF) > 0x7F } + 2
        val result = frames(body.copyOfRange(0, cut), body.copyOfRange(cut, body.size))
        assertEquals(1, result.size)
        assertTrue(result[0].data!!.contains(emoji))
    }

    @Test
    fun largePayloadsDoNotCorruptState() {
        val big = "{\"c\":\"" + "x".repeat(40_000) + "\"}"
        val input = "data: $big\n\n".encodeToByteArray()
        val result = frames(input)
        assertEquals(1, result.size)
        assertEquals(big, result[0].data)
    }

    @Test
    fun repeatedScannerReuseAfterFlush() {
        val scanner = SseFrameScanner()
        assertTrue(scanner.feed(plain).size == 2)
        assertTrue(scanner.flush().isEmpty())
        assertEquals(2, scanner.feed(plain).size)
    }
}