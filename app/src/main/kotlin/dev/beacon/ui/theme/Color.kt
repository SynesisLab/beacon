package dev.beacon.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Claude-calibrated palette (ADR-0010, pixel-measured 2026-11-03 on Anthony's
 * Pixel with the new Claude app build — sampled from live screencaps):
 *
 * - page bg #F9F9F7 (flatter/whiter than the earlier cream guess),
 * - headline/ink #131313 (near-black, warmer than pure),
 * - muted text/icons #959493 / #817F7A,
 * - icon chip circles #F0EFEC, drawer sheet #F3F3F0,
 * - coral accent #D97757 (this is the brand coral; the deep #C96442 stays for
 *   the pressed/elevated bits).
 */

// Accent (both modes; dark gets the brighter coral)
val Coral = Color(0xFFD97757)
val CoralDeep = Color(0xFFC96442)
val CoralBright = Color(0xFFE08763)

/** Functional blue (Claude's model-sheet badges/check/links — informational, not brand). */
val InfoBlue = Color(0xFF2E62E8)
val InfoBlueContainer = Color(0xFFDCE9FD)

// Light scheme (measured)
val Paper = Color(0xFFF9F9F7)          // page bg
val LightSurface = Color(0xFFFEFEFE)   // cards / composer / sheets
val LightSurfaceVariant = Color(0xFFF0EFEC) // icon chips, user pills, tool cards
val DrawerPaper = Color(0xFFF3F3F0)    // the drawer sheet is a touch warmer
val Ink = Color(0xFF131313)
val MutedInk = Color(0xFF6E6C68)
val IconInk = Color(0xFF817F7A)        // the action-row icons

// Dark scheme — calibrated to the live dark captures (2026-11-02): Claude's
// dark is FLATTER and darker than a naive warm-gray ramp: bg ≈ #1B1A18, the
// composer/sheets ≈ #232220 (barely lifted, hairline outline), pills ≈ #3A3833.
val Umber = Color(0xFF1B1A18)
val UmberSurface = Color(0xFF232220)
/** Distinctly lighter for pills/cards (never vanish against the bg — user report). */
val UmberSurfaceVariant = Color(0xFF3A3833)
val WarmWhite = Color(0xFFF2F1EB)
val WarmMuted = Color(0xFFA9A69B)