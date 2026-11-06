package dev.beacon.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v3 chrome kit (beacon-ui-design.md §3.1/§3.4): the SIDEBAR opener is a
 * CompositionLocal provided once at the nav-host drawer — any screen (or its
 * topbar kit) can open the sidebar without callback drilling.
 */
val LocalSidebarOpener = compositionLocalOf<(() -> Unit)?> { null }

/**
 * The hamburger BUBBLE (mock topbar `ib.white`): 40dp white circle, 3-line
 * glyph — Claude-app parity; opens the sidebar.
 */
@Composable
fun SidebarBubble(modifier: Modifier = Modifier) {
    // Composable-context read: Local.current inside the click lambda is NOT
    // composable scope (the same hoisting rule as LocalDensity).
    val open: (() -> Unit)? = LocalSidebarOpener.current
    Surface(
        onClick = { open?.invoke() },
        color = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 2.dp,
        modifier = modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_hamburger),
                contentDescription = "Open sidebar",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Serif TAB/SCREEN header (mock pagehead2): hamburger bubble left · centered
 * serif title · optional trailing slot. Used by Chats home / Notes / Files and
 * other list surfaces; editors keep their back-arrow bars.
 */
@Composable
fun TabHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 19.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.align(Alignment.Center),
        )
        SidebarBubble(Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
        if (trailing != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}
