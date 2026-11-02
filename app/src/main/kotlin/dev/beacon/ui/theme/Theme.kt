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
    primary = BrandIndigo,
    onPrimary = Color(0xFF10131A),
    secondary = BrandAmber,
    onSecondary = Color(0xFF10131A),
    background = BrandInk,
    onBackground = BrandOnSurface,
    surface = BrandSurface,
    onSurface = BrandOnSurface,
    surfaceVariant = BrandSurfaceHigh,
    onSurfaceVariant = BrandOnSurfaceDim,
)

private val LightScheme = lightColorScheme(
    primary = BrandIndigoLight,
    onPrimary = Color.White,
    secondary = BrandAmber,
    onSecondary = Color(0xFF10131A),
    background = BrandLightSurface,
    onBackground = BrandLightOnSurface,
    surface = BrandLightSurface,
    onSurface = BrandLightOnSurface,
    surfaceVariant = Color(0xFFE8EAF3),
    onSurfaceVariant = Color(0xFF5A6070),
)

/**
 * Brand scheme is the default; dynamic color is opt-in (agent.md §3: optional).
 * When [useDynamicColor] is true on Android 12+, the wallpaper palette wins.
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