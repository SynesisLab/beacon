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

// Dark scheme
val Umber = Color(0xFF262624)
val UmberSurface = Color(0xFF30302E)
/** Distinctly lighter for bubbles/cards so they never vanish against the bg (user report). */
val UmberSurfaceVariant = Color(0xFF40403C)
val WarmWhite = Color(0xFFF5F4EE)
val WarmMuted = Color(0xFFA8A396)