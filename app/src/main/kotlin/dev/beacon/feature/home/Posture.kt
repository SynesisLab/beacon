package dev.beacon.feature.home

/**
 * v4.0 "FoldAware" posture model (beacon-foldable-design.md §1, §5 §11.4 posture
 * contract). This file is deliberately PURE Kotlin (no Android/Compose imports)
 * so the mapping rules are plain-JUnit testable; the WindowManager adapter lives
 * in PostureTracker.kt.
 *
 * Posture is app state, not device identity: no OEM lists, no hardcoded Fold
 * dimensions — only WindowLayoutInfo's FoldingFeature + a width breakpoint.
 */

/** The three layout modes. Every screen supports all three except Onboarding (single-pane-forced). */
enum class BeaconPosture {
    /** Flat, width < 600dp — the v3 spec unchanged. */
    Phone,
    /** Unfolded / wide (≥600dp flat, or half-opened on a VERTICAL hinge = pane divider). */
    Book,
    /** Half-opened on a HORIZONTAL hinge (laptop posture): top pane far, bottom pane near. */
    Tabletop,
}

/** Mirror of FoldingFeature.State, decoupled from androidx.window. */
enum class FoldState { FLAT, HALF_OPENED }

/** Mirror of FoldingFeature.Orientation. HORIZONTAL = the hinge line runs horizontally (top/bottom panes). */
enum class FoldOrientation { HORIZONTAL, VERTICAL }

/** Mirror of FoldingFeature.OcclusionType. FULL = hard gap; NONE = soft 1dp hairline divider. */
enum class FoldOcclusion { NONE, FULL }

/** Window-coordinate rectangle, in px. */
data class FoldBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val widthPx: Int get() = (right - left).coerceAtLeast(0)
    val heightPx: Int get() = (bottom - top).coerceAtLeast(0)
}

/** Device-agnostic fold description distilled from WindowLayoutInfo. */
data class FoldSpec(
    val state: FoldState,
    val orientation: FoldOrientation,
    val occlusion: FoldOcclusion,
    val bounds: FoldBounds,
)

/**
 * The hinge band as layout input (§4.2: separations/bounds consumed as
 * WindowInsets-style padding — content never renders under an occluded hinge).
 */
data class HingeBand(
    /** For a horizontal hinge (tabletop): height of the FAR (top) pane, in px. */
    val topPanePx: Int,
    /** The occluded band itself, in px (0 when occlusion NONE → hairline instead). */
    val gapPx: Int,
    /** For a horizontal hinge (tabletop): height of the NEAR (bottom) pane, in px. */
    val bottomPanePx: Int,
    /** For a vertical hinge (book): left edge of the divider band, in px. */
    val leftPx: Int,
    /** For a vertical hinge (book): band width, in px. */
    val widthPx: Int,
    /** FULL occlusion → hard gap (two screens); NONE → 1dp hairline divider. */
    val full: Boolean,
    /** HORIZONTAL band splits top/bottom (tabletop); VERTICAL splits left/right (book). */
    val orientation: FoldOrientation,
) {
    val isHorizontal: Boolean get() = orientation == FoldOrientation.HORIZONTAL
}

/** App-state posture snapshot, collected at each route root. */
data class PostureState(
    val posture: BeaconPosture,
    val fold: FoldSpec?,
    val hinge: HingeBand?,
    val widthDp: Int,
    val widthPx: Int,
    val heightPx: Int,
) {
    val isPhone: Boolean get() = posture == BeaconPosture.Phone
    val isBook: Boolean get() = posture == BeaconPosture.Book
    val isTabletop: Boolean get() = posture == BeaconPosture.Tabletop
    /** Companion artifact pane docks only in book at ≥ ~840dp window width (§2.1/§2.3b). */
    val canDockArtifacts: Boolean get() = isBook && widthDp >= 840

    companion object {
        val PHONE = PostureState(BeaconPosture.Phone, null, null, 0, 0, 0)
    }
}

/** Width breakpoint between phone and book (§1: flat, width < 600dp → phone). */
const val BOOK_WIDTH_BREAKPOINT_DP = 600

/** Width at which the chat's artifact pane docks alongside the thread (§2.1). */
const val DOCK_WIDTH_BREAKPOINT_DP = 840

/**
 * §1 posture table:
 * - half-opened, hinge HORIZONTAL → [BeaconPosture.Tabletop]
 * - half-opened, hinge VERTICAL → [BeaconPosture.Book] (hinge = pane divider)
 * - otherwise (FLAT / FLIPPED) the width class decides: <600dp → Phone, ≥600dp → Book
 */
fun resolvePosture(windowWidthDp: Int, fold: FoldSpec?): BeaconPosture = when {
    fold != null && fold.state == FoldState.HALF_OPENED && fold.orientation == FoldOrientation.HORIZONTAL ->
        BeaconPosture.Tabletop
    fold != null && fold.state == FoldState.HALF_OPENED && fold.orientation == FoldOrientation.VERTICAL ->
        BeaconPosture.Book
    windowWidthDp < BOOK_WIDTH_BREAKPOINT_DP -> BeaconPosture.Phone
    else -> BeaconPosture.Book
}

/**
 * §4.2 hinge gap: fold bounds → pane geometry. For a horizontal hinge the band
 * spans the full window width, splitting it into top (far) / gap / bottom
 * (near); for a vertical hinge it is a left/right divider band. Occlusion
 * FULL keeps gapPx as a hard gap; NONE collapses it to a 1dp hairline.
 */
fun computeHingeBand(fold: FoldSpec?, windowWidthPx: Int, windowHeightPx: Int): HingeBand? {
    if (fold == null) return null
    val b = fold.bounds
    return when (fold.orientation) {
        FoldOrientation.HORIZONTAL -> HingeBand(
            topPanePx = b.top.coerceIn(0, windowHeightPx),
            gapPx = if (fold.occlusion == FoldOcclusion.FULL) b.heightPx else 0,
            bottomPanePx = (windowHeightPx - b.bottom).coerceAtLeast(0),
            leftPx = b.left,
            widthPx = b.widthPx,
            full = fold.occlusion == FoldOcclusion.FULL,
            orientation = FoldOrientation.HORIZONTAL,
        )
        FoldOrientation.VERTICAL -> HingeBand(
            topPanePx = 0,
            gapPx = 0,
            bottomPanePx = 0,
            leftPx = b.left.coerceIn(0, windowWidthPx),
            widthPx = if (fold.occlusion == FoldOcclusion.FULL) b.widthPx else 0,
            full = fold.occlusion == FoldOcclusion.FULL,
            orientation = FoldOrientation.VERTICAL,
        )
    }
}

/** Full snapshot from raw inputs. */
fun postureState(
    windowWidthDp: Int,
    windowWidthPx: Int,
    windowHeightPx: Int,
    fold: FoldSpec?,
): PostureState {
    val posture = resolvePosture(windowWidthDp, fold)
    // In tabletop the hinge always splits top/bottom; in book a VERTICAL
    // half-open hinge is the pane divider; a horizontal fold while "book"
    // (flat-wide) doesn't exist — computeHingeBand keys off the fold itself.
    val hinge = computeHingeBand(fold, windowWidthPx, windowHeightPx)
    return PostureState(posture, fold, hinge, windowWidthDp, windowWidthPx, windowHeightPx)
}
