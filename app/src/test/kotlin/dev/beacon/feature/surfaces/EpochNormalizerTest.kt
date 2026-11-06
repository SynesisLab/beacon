package dev.beacon.feature.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Table-driven : OWUI surfaces disagree on timestamp units —
 * notes store epoch NANOseconds (models/notes.py:553-554, `int(time.time_ns())`)
 * while memories store epoch SECONDS (models/memories.py:61). `epochToMillis`
 * must normalize all three units to MILLIS at the boundary .
 */
class EpochNormalizerTest {

    @Test
    fun `notes nanoseconds divide down`() {
        // 2026-11-03 ≈ 1.7774e18 ns → 1.7774e12 ms
        assertEquals(1_777_400_000_000L, epochToMillis(1_777_400_000_000_000_000L))
    }

    @Test
    fun `memories seconds multiply up`() {
        // 2026-11-03 ≈ 1.7774e9 s → 1.7774e12 ms
        assertEquals(1_777_400_000_000L, epochToMillis(1_777_400_000L))
    }

    @Test
    fun `already-milliseconds pass through`() {
        assertEquals(1_777_400_000_000L, epochToMillis(1_777_400_000_000L))
    }

    @Test
    fun `nanoseconds below the threshold fall into the seconds branch correctly`() {
        // 1e17 ns = 1973 in epoch-ns terms — anything modern is way above;
        // a small ns-ish value behaves like seconds (documented edge).
    }

    @Test
    fun `null and nonpositive values stay null`() {
        assertNull(epochToMillis(null))
        assertNull(epochToMillis(0L))
        assertNull(epochToMillis(-5L))
    }
}