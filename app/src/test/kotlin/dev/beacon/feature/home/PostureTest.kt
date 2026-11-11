package dev.beacon.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4 FoldAware posture contract (beacon-foldable-design.md §1/§4.2, §11.4):
 * pure mapping rules — no OEM lists, width breakpoint + FoldingFeature only.
 */
class PostureTest {

    private fun fold(
        state: FoldState,
        orientation: FoldOrientation,
        occlusion: FoldOcclusion = FoldOcclusion.NONE,
        bounds: FoldBounds = FoldBounds(0, 300, 700, 340),
    ) = FoldSpec(state, orientation, occlusion, bounds)

    @Test
    fun `flat narrow is phone`() {
        assertEquals(BeaconPosture.Phone, resolvePosture(411, null))
        assertEquals(BeaconPosture.Phone, resolvePosture(599, null))
    }

    @Test
    fun `flat wide is book`() {
        assertEquals(BeaconPosture.Book, resolvePosture(600, null))
        assertEquals(BeaconPosture.Book, resolvePosture(840, null))
        assertEquals(BeaconPosture.Book, resolvePosture(1280, null))
    }

    @Test
    fun `flat fold feature still width-class decided`() {
        // FLIPPED / fully open flat → width class decides (§1 row 5).
        val flat = fold(FoldState.FLAT, FoldOrientation.HORIZONTAL)
        assertEquals(BeaconPosture.Phone, resolvePosture(411, flat))
        assertEquals(BeaconPosture.Book, resolvePosture(900, flat))
    }

    @Test
    fun `half-opened horizontal hinge is tabletop at any width`() {
        val half = fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL, FoldOcclusion.FULL)
        assertEquals(BeaconPosture.Tabletop, resolvePosture(411, half))
        assertEquals(BeaconPosture.Tabletop, resolvePosture(1100, half))
    }

    @Test
    fun `half-opened vertical hinge is book - hinge as pane divider`() {
        val half = fold(
            FoldState.HALF_OPENED,
            FoldOrientation.VERTICAL,
            FoldOcclusion.NONE,
            FoldBounds(340, 0, 344, 700),
        )
        assertEquals(BeaconPosture.Book, resolvePosture(411, half))
        assertEquals(BeaconPosture.Book, resolvePosture(700, half))
    }

    @Test
    fun `hinge band horizontal full occlusion keeps a hard gap`() {
        val half = fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL, FoldOcclusion.FULL, FoldBounds(0, 300, 700, 340))
        val band = computeHingeBand(half, 700, 560)!!
        assertTrue(band.isHorizontal)
        assertEquals(300, band.topPanePx)
        assertEquals(40, band.gapPx) // the occluded band is excluded from both panes
        assertEquals(220, band.bottomPanePx) // 560 - 340
        assertTrue(band.full)
    }

    @Test
    fun `hinge band none occlusion collapses to hairline`() {
        val half = fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL, FoldOcclusion.NONE, FoldBounds(0, 300, 700, 340))
        val band = computeHingeBand(half, 700, 560)!!
        assertEquals(0, band.gapPx)
        assertFalse(band.full)
        // panes still split at the hinge line
        assertEquals(300, band.topPanePx)
        assertEquals(220, band.bottomPanePx)
    }

    @Test
    fun `hinge band vertical full is a hard left-right gap`() {
        val half = fold(FoldState.HALF_OPENED, FoldOrientation.VERTICAL, FoldOcclusion.FULL, FoldBounds(340, 0, 380, 560))
        val band = computeHingeBand(half, 700, 560)!!
        assertFalse(band.isHorizontal)
        assertEquals(340, band.leftPx)
        assertEquals(40, band.widthPx)
        assertTrue(band.full)
    }

    @Test
    fun `no fold means no hinge band`() {
        assertNull(computeHingeBand(null, 700, 560))
    }

    @Test
    fun `dock gate is book at 840dp and up only`() {
        val bookNarrow = postureState(700, 1400, 1000, null)
        val bookWide = postureState(900, 1800, 1000, null)
        val phone = postureState(411, 822, 1782, null)
        assertFalse(bookNarrow.canDockArtifacts)
        assertTrue(bookWide.canDockArtifacts)
        assertFalse(phone.canDockArtifacts)
        assertTrue(bookNarrow.isBook)
        assertTrue(phone.isPhone)
    }

    @Test
    fun `tabletop snapshot carries the hinge band`() {
        val half = fold(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL, FoldOcclusion.FULL, FoldBounds(0, 280, 640, 320))
        val state = postureState(640, 640, 560, half)
        assertEquals(BeaconPosture.Tabletop, state.posture)
        assertTrue(state.isTabletop)
        assertEquals(280, state.hinge!!.topPanePx)
        assertEquals(40, state.hinge.gapPx)
        assertEquals(240, state.hinge.bottomPanePx)
    }
}
