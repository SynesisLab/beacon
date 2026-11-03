package dev.beacon.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.beacon.ui.theme.Coral

/**
 * The landing-page critter (user request 2026-11-03: "a cute avatar inspired
 * by Claude's" pixel mascot). ORIGINAL design — chunky pixel creature in the
 * Beacon palette: a terracotta body with a little left-facing snout, ink
 * square eyes, three stub legs, and a floating heart right above the snout
 * (Beacon = warmth + the heart of the mark, drawn in our own shape; nothing
 * copied from Anthropic's assets, interface-mapping.md rule). The grid is a
 * string map — pixel-perfect, resolution-independent, zero assets.
 *
 * Legend: '.' empty · 'B' body · 'E' eye · 'H' heart.
 */
private val CRITTER_MAP = listOf(
    "...H.H..............",
    "..HHHHH.............",
    "...HHH..............",
    "....H...............",
    ".....BBBBBBBBBBBB...",
    "....BBBBBBBBBBBBBB..",
    "....BBBBBBBBBBBBBB..",
    "....BBBEEBBBBEEBBB..",
    "....BBBEEBBBBEEBBB..",
    "....BBBBBBBBBBBBBB..",
    "....BBBBBBBBBBBBBB..",
    "....BBBBBBBBBBBBBB..",
    "....BBBBBBBBBBBBBB..",
    ".....BB..BB..BB.....",
)

private val BodyColor = Coral
private val EyeColor = Color(0xFF131313)
private val HeartColor = Color(0xFFE14B4B)

@Composable
fun LandingCritter(size: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "critter")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "critter-pulse",
    )
    val grid = remember { CRITTER_MAP }
    val heightPx = with(LocalDensity.current) { size.toPx() }
    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
                alpha = 0.92f + 0.08f * ((pulse - 0.97f) / 0.08f).coerceIn(0f, 1f)
            },
    ) {
        val cellW = this.size.width / grid[0].length
        val cellH = heightPx / grid.size
        grid.forEachIndexed { row, line ->
            line.forEachIndexed { col, c ->
                val color = when (c) {
                    'B' -> BodyColor
                    'E' -> EyeColor
                    'H' -> HeartColor
                    else -> return@forEachIndexed
                }
                drawRect(
                    color = color,
                    topLeft = Offset(col * cellW, row * cellH),
                    size = androidx.compose.ui.geometry.Size(cellW + 0.6f, cellH + 0.6f),
                )
            }
        }
    }
}