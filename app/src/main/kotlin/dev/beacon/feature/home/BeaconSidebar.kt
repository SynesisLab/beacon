package dev.beacon.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.beacon.core.data.local.ChatEntity

/**
 * The v3 SLIDE-IN SIDEBAR (primary navigation; beacon-ui-design.md §2/§3.4,
 * mockup 03) — Claude's drawer anatomy (wordmark · rows · recents · bottom
 * identity bar + ＋ New chat) × the Open WebUI web sidebar (Search · Notes ·
 * Workspace · gated rows · date groups). Gated rows VANISH without their
 * flags. Model switching deliberately stays on the top context chip.
 *
 * State-driven (goldens render without a VM); the host provides data + actions.
 */
data class SidebarState(
    val chats: List<ChatEntity> = emptyList(),
    val folders: List<dev.beacon.core.api.FolderRow> = emptyList(),
    val search: String = "",
    val memoriesGate: Boolean = false,
    val channelsGate: Boolean = false,
    val userName: String? = null,
    val userEmail: String? = null,
    val userRole: String? = null,
    val serverVersion: String? = null,
    val appVersion: String = "1.0",
    /** The active route's row key (chats/notes/files/workspace/…) for the ink row. */
    val activeRow: String = "chats",
    /**
     * NAV rows the user toggled OFF in Settings → Sidebar (2026-11-06):
     * hidden rows VANISH exactly like gated rows (never gray).
     */
    val hiddenRows: Set<String> = emptySet(),
)

data class SidebarActions(
    val search: (String) -> Unit = {},
    /** NAV rows: "chats" / "notes" / "files" / "workspace" / "memories" / "channels" — Settings opens from the identity bar. */
    val goto: (String) -> Unit = {},
    val openChat: (String) -> Unit = {},
    /** Chat/folder ⋯ / long-press → the shared row-actions sheet (host-level). */
    val chatActions: (ChatEntity) -> Unit = {},
    /** Chats-section ＋ → new-folder dialog (host-level sheet). */
    val createFolder: () -> Unit = {},
    val newChat: () -> Unit = {},
)

@Composable
fun BeaconSidebar(
    state: SidebarState,
    actions: SidebarActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── wordmark ──────────────────────────────────────────────────────
        Column(Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp)) {
            Text(
                "Beacon",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                ("v" + state.appVersion) + (state.serverVersion?.let { " · server v$it" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ── search ────────────────────────────────────────────────────────
        OutlinedTextField(
            value = state.search,
            onValueChange = actions.search,
            singleLine = true,
            shape = RoundedCornerShape(50),
            placeholder = { Text("Search chats", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .height(52.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
        // ── NAV rows ──────────────────────────────────────────────────────
        Text("NAVIGATE", style = sidebarSection(), modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp))
        SidebarRow("chats", "◌", "Chats", state.activeRow == "chats", actions.goto)
        if ("notes" !in state.hiddenRows) {
            SidebarRow("notes", "▤", "Notes", state.activeRow == "notes", actions.goto)
        }
        if ("files" !in state.hiddenRows) {
            SidebarRow("files", "▥", "Files", state.activeRow == "files", actions.goto)
        }
        if ("workspace" !in state.hiddenRows) {
            SidebarRow("workspace", "▦", "Workspace", state.activeRow == "workspace", actions.goto)
        }
        if (state.memoriesGate && "memories" !in state.hiddenRows) {
            SidebarRow("memories", "◍", "Memories", state.activeRow == "memories", actions.goto)
        }
        if (state.channelsGate && "channels" !in state.hiddenRows) {
            SidebarRow("channels", "◈", "Channels", state.activeRow == "channels", actions.goto)
        }
        // Settings has NO nav row — it opens from the bottom identity bar.
        // ── CHATS (mirrors the home list) ─────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp),
        ) {
            Text("CHATS", style = sidebarSection())
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = actions.createFolder, modifier = Modifier.size(26.dp)) {
                Text("＋", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .semantics { testTag = "sidebar_chats" },
        ) {
            state.chats.filter { it.pinned && !it.archived }.let { pinned ->
                if (pinned.isNotEmpty()) {
                    items(pinned, key = { "sb_pin_" + it.chatId }) { chat ->
                        SideChatRow(chat, "✦",
                            open = { actions.openChat(chat.chatId) },
                            actions = { actions.chatActions(chat) },
                            pinned = true)
                    }
                }
            }
            if (state.folders.isNotEmpty()) {
                items(state.folders, key = { "sb_fold_" + it.id }) { folder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { /* folders drill into their chats via the home */ actions.goto("folders") }
                            .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Text("▸", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(26.dp))
                        Text(folder.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            folder.unread_count.takeIf { it > 0 }?.toString() ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            bucketChats(state.chats).forEach { (label, chatsInBucket) ->
                if (chatsInBucket.isNotEmpty()) {
                    items(chatsInBucket, key = { "sb_" + label + it.chatId }) { chat ->
                        SideChatRow(chat, if (label == "Today") "◷" else "◷",
                            open = { actions.openChat(chat.chatId) },
                            actions = { actions.chatActions(chat) },
                            pinned = false)
                    }
                }
            }
        }
        // ── bottom identity bar (pinned) ──────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        // Settings lives here (the nav row is gone): the whole
                        // identity grouping opens it.
                        .clickable { actions.goto("settings") },
                ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFEFF3FE), CircleShape),
                ) {
                    Text(
                        initialsOf(state.userName, state.userEmail),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3565F2),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        state.userName ?: "Signed in",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.userRole?.let { role ->
                        Text(
                            role.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(50))
                        .clickable { actions.newChat() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        "＋ New chat",
                        color = MaterialTheme.colorScheme.background,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** One NAV row: glyph · label · active = ink + drawer-fill tint. */
@Composable
private fun SidebarRow(key: String, glyph: String, label: String, active: Boolean, goto: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { goto(key) }
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .semantics { testTag = "sidebar_row_$key" },
    ) {
        Text(glyph, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(26.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun SideChatRow(chat: ChatEntity, glyph: String, open: () -> Unit, actions: () -> Unit, pinned: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open() }
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            glyph,
            fontSize = 12.sp,
            color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(26.dp),
        )
        Text(
            chat.title.ifBlank { "Untitled chat" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "⋯",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { actions() }
                .padding(start = 6.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
        )
    }
}

@Composable
private fun sidebarSection() = MaterialTheme.typography.labelSmall.copy(
    fontSize = 10.5.sp,
    letterSpacing = 0.9.sp,
    fontWeight = FontWeight.Bold,
)

/** "Anthony Chen" → "AC"; falls back to the email's first letters. */
internal fun initialsOf(name: String?, email: String?): String =
    (name ?: email)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }
        ?.take(2)?.mapNotNull { it.firstOrNull()?.uppercase() }?.joinToString("")
        ?.takeIf { it.isNotBlank() } ?: "·"
