package dev.beacon.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Claude-style bottom tab bar (float pill, active = gray chip wrap) — v2.0
 * "simplified" tabs (beacon-ui-design.md §2): **Chats / Notes / Files / More**.
 * The drawer died (recents live in Chats home); Folders + Chat tabs retired.
 * Files = the one-list file surface; More = the everything-else list.
 */
data class TabDef(
    val key: String,
    val label: String,
    val icon: Int,
)

val BeaconTabs = listOf(
    TabDef("chats", "Chats", dev.beacon.R.drawable.ic_beacon_logo),
    TabDef("notes", "Notes", dev.beacon.R.drawable.ic_tab_notes),
    TabDef("files", "Files", dev.beacon.R.drawable.ic_tab_files),
    TabDef("more", "More", dev.beacon.R.drawable.ic_tab_more),
)

@Composable
fun BeaconTabBar(
    active: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(100),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BeaconTabs.forEach { tab ->
                val isActive = tab.key == active
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab.key) }
                        .height(62.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = if (isActive) {
                            Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(100))
                                .padding(horizontal = 18.dp, vertical = 8.dp)
                        } else {
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        },
                    ) {
                        Icon(
                            androidx.compose.ui.res.painterResource(tab.icon),
                            contentDescription = tab.label,
                            tint = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 11.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}