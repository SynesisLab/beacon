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
    tertiary = InfoBlue,
    onTertiary = Umber,
    tertiaryContainer = InfoBlueContainer,
    onTertiaryContainer = Color(0xFF0F1B3D),
    background = Umber,
    onBackground = WarmWhite,
    surface = UmberSurface,
    onSurface = WarmWhite,
    surfaceVariant = UmberSurfaceVariant,
    onSurfaceVariant = WarmMuted,
    surfaceContainerLow = UmberSurface, // drawer/sheet = barely lifted (measured 2026-11-02)
    outline = Color(0xFF3B3934), // the hairline lift on the flat dark
)

private val LightScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDE4),
    onPrimaryContainer = Ink,
    secondary = MutedInk,
    onSecondary = Color.White,
    tertiary = InfoBlue,
    onTertiary = Color.White,
    tertiaryContainer = InfoBlueContainer,
    onTertiaryContainer = Color(0xFF0F1B3D),
    background = Paper,
    onBackground = Ink,
    surface = LightSurface,
    onSurface = Ink,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = MutedInk,
    surfaceContainerLow = DrawerPaper, // the drawer sheet = a touch warmer
    outline = Color(0xFFDEDCD7),
)

/**
 * Claude-warm brand scheme is the default; dynamic color stays opt-in .
 */
val LocalBeaconDark = androidx.compose.runtime.compositionLocalOf { false }

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
    // Forced Appearance modes (user request 2026-11-03) ride here: WebView
    // cards read LocalBeaconDark instead of the system flag (HtmlCardPane).
    androidx.compose.runtime.CompositionLocalProvider(LocalBeaconDark provides darkTheme) {
        MaterialTheme(
            colorScheme = scheme,
            typography = BeaconTypography,
            content = content,
        )
    }
}