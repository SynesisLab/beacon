package dev.beacon.core.api

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture replay : the payload captured LIVE from Anthony's
 * terminal server round 2026-11-03 (files/list?directory=/home/user) — the
 * entry `modified`/`created` are FRACTIONAL epoch-seconds doubles, which a
 * Long-typed field rejected and killed the whole listing ("Failed to parse
 * literal '1777343786.2236419' as a long value"). TsFileEntry must decode
 * this tolerantly; rows compose cwd+name paths.
 */
class TerminalEntryDecodeTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val liveEntries = """
        {"entries":[
          {"name":".bash_history","type":"file","size":130,
           "modified":1777343786.2236419,"created":1777343786.1836417,"writable":true},
          {"name":".cache","type":"directory","size":4096,
           "modified":1757780486.0,"created":1757780486.0,"writable":true},
          {"name":".npm","type":"directory","size":4096,"modified":1775382000}
        ],"writable":true}
    """.trimIndent()

    @Test
    fun `live terminal-server entries decode with fractional modified`() {
        val obj = json.parseToJsonElement(liveEntries) as JsonObject
        val entries = json.decodeFromJsonElement(
            ListSerializer(TsFileEntry.serializer()), obj["entries"]!!,
        )
        assertEquals(3, entries.size)
        // The float-second file: readable as epoch millis.
        val bash = entries[0]
        assertEquals(".bash_history", bash.name)
        assertEquals(false, bash.isDirectory)
        assertEquals(130L, bash.size)
        // 1777343786.2236419 s → 1777343786223 ms (truncate, not round up).
        assertEquals(1_777_343_786_223L, (bash.modified!! * 1_000.0).toLong())
        val cache = entries[1]
        assertTrue(cache.isDirectory)
    }

    @Test
    fun `directory type drives isDirectory without a path field`() {
        val one = json.parseToJsonElement("""{"name":"open_terminal","type":"directory","writable":true}""")
        val entry = json.decodeFromJsonElement(TsFileEntry.serializer(), one)
        assertTrue(entry.isDirectory)
        assertEquals("open_terminal", entry.name)
    }
}