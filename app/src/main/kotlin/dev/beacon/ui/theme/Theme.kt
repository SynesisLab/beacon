package dev.beacon.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkScheme = darkColorScheme(
    primary = CoralBright,
    onPrimary = Umber,
    primaryContainer = CoralDeep,
    onPrimaryContainer = WarmWhite,
    secondary = WarmMuted,
    onSecondary = Umber,
    background = Umber,
    onBackground = WarmWhite,
    surface = UmberSurface,
    onSurface = WarmWhite,
    surfaceVariant = UmberSurfaceVariant,
    onSurfaceVariant = WarmMuted,
    outline = Color(0xFF57534E),
)

private val LightScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDE4),
    onPrimaryContainer = Ink,
    secondary = MutedInk,
    onSecondary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = LightSurface,
    onSurface = Ink,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFD6D3CB),
)

/**
 * Claude-warm brand scheme is the default; dynamic color stays opt-in (agent.md §3).
 */
@Composable
fun BeaconTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = BeaconTypography,
        content = content,
    )
}