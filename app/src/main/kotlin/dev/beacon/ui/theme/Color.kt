package dev.beacon.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Claude-style warm palette (ADR-0010): cream light, umber dark, coral accent —
 * "similar to Claude's default color", calm and literary rather than cool-indigo.
 */

// Accent (both modes; dark gets the brighter coral)
val Coral = Color(0xFFD97757)
val CoralDeep = Color(0xFFC96442)
val CoralBright = Color(0xFFE08763)

// Light scheme
val Cream = Color(0xFFFAF9F5)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0EEE4)
val Ink = Color(0xFF3D3929)
val MutedInk = Color(0xFF6B6657)

// Dark scheme
val Umber = Color(0xFF262624)
val UmberSurface = Color(0xFF30302E)
/** Distinctly lighter for bubbles/cards so they never vanish against the bg (user report). */
val UmberSurfaceVariant = Color(0xFF40403C)
val WarmWhite = Color(0xFFF5F4EE)
val WarmMuted = Color(0xFFA8A396)