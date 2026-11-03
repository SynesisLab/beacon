package dev.beacon.feature.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beacon.core.data.ServerSettings
import dev.beacon.core.data.SessionStore
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.feature.home.HomeViewModel
import dev.beacon.feature.surfaces.SurfaceViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The chats drawer (interface copy 2026-11-02; fixed trio 2026-11-03). Claude's
 * sidebar structure maps onto the OWUI surfaces (gated by the server's
 * /api/config features): Chats → the recents; Projects → Folders; Notes /
 * Memories / Channels per the capability gates. The fixed block = Workspace /
 * Terminal / Settings (Files lives in the M6-b tab bar). The bottom = the
 * account row (avatar + name) → Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsDrawer(
    listViewModel: HomeViewModel,
    surfaces: SurfaceViewModel,
    onOpenChat: (ChatEntity) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSurface: (String) -> Unit,
    onOpenTerminal: () -> Unit,
    onClose: () -> Unit,
) {
    val state by listViewModel.ui.collectAsStateWithLifecycle()
    val surfaceState by surfaces.state.collectAsStateWithLifecycle()
    val gates = surfaces.state.value
    var menuFor by remember { mutableStateOf<ChatEntity?>(null) }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        // Logo block (user request): the beacon mark + "Beacon" + the tiny line.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(34.dp),
            ) {
                // The simplified mark (ic_beacon_logo): a lamp dot + a clean
                // three-ray beam — designed symbol, not the full launcher glyph.
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_beacon_logo),
                        contentDescription = "Beacon",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column {
                Text("Beacon", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "a client for Open WebUI",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DrawerRow("+ New chat", onClick = { onNewChat(); onClose() })
        // Folders/Notes live in the tab bar (de-dup, user request 2026-11-03);
        // the gated surface rows stay for what has no tab.
        GatedRow("Memories", gates.memoriesGate, onClick = { onOpenSurface("memories"); onClose() })
        GatedRow("Channels", gates.channelsGate, onClick = { onOpenSurface("channels"); onClose() })
        // The fixed trio (user request 2026-11-03): Workspace/terminal/settings.
        // Files lives in the tab bar now; Workspace is ungated there (the
        // screen degrades gracefully, §16.5).
        DrawerRow("Workspace", onClick = { onOpenSurface("workspace"); onClose() })
        DrawerRow("Terminal", onClick = { onOpenTerminal(); onClose() })
        DrawerRow("Settings", onClick = { onOpenSettings(); onClose() })
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Recents",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.chats, key = { it.chatId }) { chat ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChat(chat); onClose() }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            chat.title.ifBlank { "Untitled" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            (if (chat.pinned) "📌 " else "") + formatWhen(chat.updatedAt),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { menuFor = chat }) { Text("•••") }
                }
            }
        }
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        AccountRow(
            name = surfaceState.accountName,
            email = surfaceState.accountEmail,
            onClick = { onOpenSettings(); onClose() },
        )
    }

    menuFor?.let { chat ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(16.dp)) {
                TextButton(
                    onClick = { listViewModel.togglePin(chat.chatId); menuFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (chat.pinned) "Unpin" else "Pin") }
                TextButton(
                    onClick = { listViewModel.toggleArchive(chat.chatId); menuFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Archive") }
                TextButton(
                    onClick = { listViewModel.delete(chat.chatId); menuFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun DrawerRow(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    ) { Text(label, fontWeight = FontWeight.Medium) }
}

/** Capability-gated rows (agent.md §16.6 — hidden, never crashed, when absent). */
@Composable
private fun GatedRow(label: String, visible: Boolean, onClick: () -> Unit) {
    if (visible) DrawerRow(label, onClick)
}

@Composable
private fun AccountRow(name: String?, email: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        ) {
            val initials = (name ?: "?").trim().split(' ').take(2)
                .mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
            Box(contentAlignment = Alignment.Center) {
                Text(initials.ifBlank { "?" }, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Column {
            Text(name ?: "Account", style = MaterialTheme.typography.titleMedium)
            email?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatWhen(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    val zoned = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault())
    return zoned.toLocalDate().toString() + " " + zoned.toLocalTime().withSecond(0).withNano(0).toString().take(5)
}