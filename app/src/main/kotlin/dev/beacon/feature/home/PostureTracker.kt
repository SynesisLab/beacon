package dev.beacon.feature.home

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.roundToInt

/**
 * v4 FoldAware tracker (beacon-foldable-design.md §1): the single
 * [BeaconPosture] flow, collected at the route root from
 * WindowInfoTracker.windowLayoutInfo + the window width class. Pure mapping
 * rules live in Posture.kt (plain-JUnit tested); this file is only the
 * androidx.window adapter + the composition locals every screen reads.
 */

/** The physical hinge renders near-black in both schemes (mockup F5 hingeband). */
private val HingeHardware = Color(0xFF141311)

/** The active posture at this route root. Default = phone (goldens/tests unaffected). */
val LocalPosture = staticCompositionLocalOf { PostureState.PHONE }

/** Map a WindowLayoutInfo to the pure fold spec (null when no FoldingFeature). */
fun foldSpecOf(info: WindowLayoutInfo?): FoldSpec? {
    if (info == null) return null
    val features = info.displayFeatures.filterIsInstance<FoldingFeature>()
    // A half-opened feature wins over a flat one (dual-screen / folded states).
    val f = features.firstOrNull { it.state == FoldingFeature.State.HALF_OPENED } ?: features.firstOrNull()
    return f?.let {
        FoldSpec(
            state = if (it.state == FoldingFeature.State.HALF_OPENED) FoldState.HALF_OPENED else FoldState.FLAT,
            orientation = if (it.orientation == FoldingFeature.Orientation.HORIZONTAL) {
                FoldOrientation.HORIZONTAL
            } else {
                FoldOrientation.VERTICAL
            },
            occlusion = if (it.occlusionType == FoldingFeature.OcclusionType.FULL) FoldOcclusion.FULL else FoldOcclusion.NONE,
            bounds = FoldBounds(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom),
        )
    }
}

/**
 * Collect the posture flow for [activity]. Layout switches are recompositions,
 * never re-navigation (§1): the caller must NOT key anything by posture, and
 * the activity opts out of config-driven recreation for hinge-only changes
 * (AndroidManifest configChanges) so back stack / scroll / drafts survive.
 */
@Composable
fun rememberBeaconPosture(
    activity: Activity,
    widthSizeClass: WindowWidthSizeClass,
): PostureState {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthDp = configuration.screenWidthDp
    val widthPx = with(density) { configuration.screenWidthDp.dp.toPx().roundToInt() }
    val heightPx = with(density) { configuration.screenHeightDp.dp.toPx().roundToInt() }
    val tracker = remember(activity) { WindowInfoTracker.getOrCreate(activity) }
    val info by tracker.windowLayoutInfo(activity).collectAsStateWithLifecycle(null)
    // Width class is the device-agnostic half of the contract (§1): Compact = <600dp.
    val widthForBreakpoint = if (widthSizeClass == WindowWidthSizeClass.Compact) widthDp.coerceAtMost(599) else widthDp.coerceAtLeast(600)
    return remember(info, widthDp, widthPx, heightPx, widthForBreakpoint) {
        postureState(widthForBreakpoint, widthPx, heightPx, foldSpecOf(info))
    }
}

/** Provide [LocalPosture] around content. */
@Composable
fun PostureProvider(posture: PostureState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPosture provides posture, content = content)
}

/**
 * §4.2 hinge gap rendered between panes: FULL occlusion → a hard dark band
 * (panes read as two screens); NONE → a 1dp hairline divider (outline token;
 * dark-mode #35332F is the dark scheme's outline). Horizontal band → top/bottom
 * split (tabletop); vertical band → left/right divider (book on a vertical hinge).
 */
@Composable
fun HingeGap(band: HingeBand, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    if (band.isHorizontal) {
        if (band.full) {
            with(density) {
                Box(modifier.fillMaxWidth().height(band.gapPx.toDp().coerceAtLeast(1.dp)).background(HingeHardware))
            }
        } else {
            androidx.compose.material3.HorizontalDivider(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    } else {
        if (band.full) {
            with(density) {
                Box(
                    modifier
                        .fillMaxHeight()
                        .width(band.widthPx.toDp().coerceAtLeast(1.dp))
                        .background(HingeHardware),
                )
            }
        } else {
            androidx.compose.material3.VerticalDivider(
                modifier = modifier,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}


