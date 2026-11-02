package dev.beacon.core.sse

import kotlin.math.max

/**
 * Byte-level incremental SSE event scanner (agent.md §3: own implementation,
 * concept-mirrors Conduit's `sse_frame_scanner`). OWUI emits `data: {json}\n\n`
 * frames (middleware.py `wrap_item`), errors as `data: {"error": …}\n\n` plus the
 * `data: [DONE]` sentinel; proxies may still speak spec SSE (comments, `event:`,
 * lone-CR lines). The scanner is spec-shaped and byte-exact:
 *
 * - byte-level: frames may split mid-CRLF and mid-UTF-8 (agent.md §17) — UTF-8 is
 *   decoded only for *complete* lines, so split sequences are impossible by design;
 * - line terminators: CRLF, lone CR, lone LF (SSE spec); a CR reaching the end of a
 *   chunk holds until the next piece decides CRLF vs CR;
 * - an empty line terminates the event (with per-OWUI shape: exactly one `data:` line);
 *   multi-line `data:` joins with '\n' (SSE spec);
 * - `:` comment lines are dropped; `id:`/`retry:` ignored; `event:` names carried;
 * - the final event may lack a trailing blank line — [flush] covers it.
 */
class SseFrameScanner {

    /** One decoded SSE event. [data] == null for heartbeat/empty-data events. */
    data class Frame(val event: String?, val data: String?) {
        val isHeartbeat: Boolean get() = data == null
    }

    private var buffer = ByteArray(BUFFER_INITIAL)
    private var bufferLength = 0
    private var scanPos = 0

    // Per-event accumulation state
    private var eventName: String? = null
    private var dataLines: MutableList<String>? = null
    private var sawDataField = false

    private var heldCrAtEob = false // a trailing CR at the end of a fed chunk

    /** Feed a byte chunk (any split point) and get every event that terminated in it. */
    fun feed(chunk: ByteArray): List<Frame> {
        ensureCapacity(bufferLength + chunk.size + HELD_CR_SLACK)
        System.arraycopy(chunk, 0, buffer, bufferLength, chunk.size)
        bufferLength += chunk.size
        return scan()
    }

    /** At stream EOF: emits a trailing unterminated event, if any. */
    fun flush(): List<Frame> {
        val result = ArrayList<Frame>(1)
        if (scanPos < bufferLength) {
            processLine(bufferToString(scanPos, bufferLength - scanPos))
            scanPos = bufferLength
        }
        completeEvent()?.let { result.add(it) }
        reset()
        return result
    }

    private fun scan(): List<Frame> {
        val frames = ArrayList<Frame>(2)

        while (scanPos < bufferLength) {
            // Find the line terminator starting at scanPos.
            var i = scanPos
            var lineEnd = -1
            var next = -1
            while (i < bufferLength) {
                val b = buffer[i]
                if (b == LF) {
                    lineEnd = i
                    next = i + 1
                    break
                }
                if (b == CR) {
                    lineEnd = i
                    next = when {
                        i + 1 < bufferLength && buffer[i + 1] == LF -> i + 2 // CRLF
                        i + 1 >= bufferLength -> LINE_PENDING // CR at end of chunk: hold
                        else -> i + 1 // lone CR terminator
                    }
                    break
                }
                i++
            }

            if (next == LINE_PENDING) {
                heldCrAtEob = true
                break // wait for the next chunk to resolve CRLF-vs-lone-CR
            }

            val line = bufferToString(scanPos, lineEnd - scanPos)
            processLine(line)

            if (line.isEmpty()) {
                completeEvent()?.let { frames.add(it) }
                resetAccumulators()
            }

            scanPos = next
            heldCrAtEob = false
        }

        if (scanPos > 0 && scanPos < bufferLength) {
            compact()
        } else if (scanPos >= bufferLength) {
            bufferLength = 0
            scanPos = 0
        }
        return frames
    }

    private fun processLine(line: String) {
        when {
            line.startsWith(":") -> Unit // comment per SSE spec
            line.startsWith("event:") -> eventName = field(line).takeIf { it.isNotEmpty() }
            line.startsWith("data:") -> {
                dataLines = dataLines ?: ArrayList()
                sawDataField = true
                dataLines!!.add(field(line))
            }
            // id:/retry:/future fields — ignored for this protocol.
        }
    }

    /** Payload for `data:` / `event:` values (one optional space after the colon). */
    private fun field(line: String): String {
        val afterColon = line.substringAfter(':')
        return if (afterColon.startsWith(" ")) afterColon.substring(1) else afterColon
    }

    private fun completeEvent(): Frame? {
        if (!sawDataField) return null
        val lines = dataLines
        val payload = when {
            lines == null -> null
            lines.size == 1 -> lines[0]
            else -> lines.joinToString("\n")
        }
        // Empty data = heartbeat (agent.md §17): the parser must never decode it.
        return Frame(eventName, payload?.takeIf { it.isNotBlank() })
    }

    private fun resetAccumulators() {
        eventName = null
        dataLines = null
        sawDataField = false
    }

    private fun reset() {
        bufferLength = 0
        scanPos = 0
        heldCrAtEob = false
        resetAccumulators()
    }

    private fun bufferToString(from: Int, length: Int): String =
        String(buffer, from, length, Charsets.UTF_8)

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var next = max(buffer.size * 2, MIN_GROWTH)
        while (next < needed) next *= 2
        buffer = buffer.copyOf(next)
    }

    private fun compact() {
        System.arraycopy(buffer, scanPos, buffer, 0, bufferLength - scanPos)
        bufferLength -= scanPos
        scanPos = 0
    }

    private companion object {
        const val LF: Byte = '\n'.code.toByte()
        const val CR: Byte = '\r'.code.toByte()
        const val LINE_PENDING = -1
        const val BUFFER_INITIAL = 8192
        const val HELD_CR_SLACK = 4
        const val MIN_GROWTH = 4096
    }
}