package dev.beacon.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Claude-style warm palette (ADR-0010), calibrated against the live-app captures
 * on Anthony's Pixel (2026-11-02): page-cream ≈ #F4F3ED (grayer than my first
 * guess), inner pills ≈ #ECE9E2, functional BLUE accents in the model sheet
 * (badges/check/link), the starburst's deeper coral ≈ #C96442.
 */

// Accent (both modes; dark gets the brighter coral)
val Coral = Color(0xFFD97757)
val CoralDeep = Color(0xFFC96442)
val CoralBright = Color(0xFFE08763)

/** Functional blue (Claude's model-sheet badges/check/links — informational, not brand). */
val InfoBlue = Color(0xFF2E62E8)
val InfoBlueContainer = Color(0xFFDCE9FD)

// Light scheme
val Cream = Color(0xFFF4F3ED)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFECE9E2)
val Ink = Color(0xFF3D3929)
val MutedInk = Color(0xFF6B6657)

// Dark scheme — calibrated to the live dark captures (2026-11-02): Claude's
// dark is FLATTER and darker than a naive warm-gray ramp: bg ≈ #1B1A18, the
// composer/sheets ≈ #232220 (barely lifted, hairline outline), pills ≈ #3A3833.
val Umber = Color(0xFF1B1A18)
val UmberSurface = Color(0xFF232220)
/** Distinctly lighter for pills/cards (never vanish against the bg — user report). */
val UmberSurfaceVariant = Color(0xFF3A3833)
val WarmWhite = Color(0xFFF2F1EB)
val WarmMuted = Color(0xFFA9A69B)