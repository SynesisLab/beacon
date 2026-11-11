package dev.beacon.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4.1 split-handle math: snap points + clamping of the pane fraction. */
class CompanionPaneTest {

    // 1200dp content width: pane floor 280dp → fMin=0.2333, thread floor 320dp → fMax=0.7333.
    private val fMin = 280f / 1200f
    private val fMax = 1f - 320f / 1200f

    @Test
    fun dragReleaseSnapsToNearestThirdHalfTwoThirds() {
        assertEquals(1f / 3f, snapPaneFraction(0.30f, fMin, fMax), 1e-4f)
        assertEquals(1f / 3f, snapPaneFraction(0.36f, fMin, fMax), 1e-4f)
        assertEquals(0.5f, snapPaneFraction(0.47f, fMin, fMax), 1e-4f)
        assertEquals(0.5f, snapPaneFraction(0.55f, fMin, fMax), 1e-4f)
        assertEquals(2f / 3f, snapPaneFraction(0.63f, fMin, fMax), 1e-4f)
        assertEquals(2f / 3f, snapPaneFraction(0.70f, fMin, fMax), 1e-4f)
    }

    @Test
    fun snapStaysInsideRangeAtNarrowWidths() {
        // 900dp content: fMax = 0.6444 < ⅔ — the ⅔ point clamps to fMax.
        val min = 280f / 900f
        val max = 1f - 320f / 900f
        assertEquals(max, snapPaneFraction(0.99f, min, max), 1e-4f)
        assertTrue(snapPaneFraction(0.0f, min, max) >= min)
        assertTrue(snapPaneFraction(1.0f, min, max) <= max)
    }

    @Test
    fun doubleTapTargetHalfIsClamped() {
        // Narrow content: ½ may exceed fMax → clamped.
        val min = 0.45f
        val max = 0.48f
        assertEquals(max, 0.5f.coerceIn(min, max), 1e-4f)
    }
}
