package dev.beacon.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.beacon.core.data.local.ChatEntity

/**
 * v4 FoldAware BOOK-mode icon rail (beacon-foldable-design.md §2.1, mockup F1):
 * the v3 drawer demoted from overlay to a slim RESIDENT rail — fixed 72dp
 * (Material rail width, never a percentage), same drawer background, same entry
 * order, labels hidden (long-press = tooltip). Wordmark shrinks to the glyph
 * (tap → the full v3 drawer expands over the content), search collapses to a
 * ⌕ popover, recents live in the ◌ popover, avatar + icon-only ＋ stay pinned
 * at the bottom. Touch targets 48dp (§4.4 book relaxation).
 */
data class RailActions(
    /** NAV rows (notes/files/workspace/memories/channels/settings). */
    val goto: (String) -> Unit = {},
    /** Recents-popover row tap → chat selection loads into the thread pane. */
    val openChat: (String) -> Unit = {},
    val chatActions: (ChatEntity) -> Unit = {},
    val newChat: () -> Unit = {},
    /** Rail glyph / popover header → expand the full v3 drawer over the content. */
    val expandDrawer: () -> Unit = {},
    val search: (String) -> Unit = {},
)

/** Fixed rail width — Material navigation-rail width, not a window percentage (§2.1). */
val RailWidth = 72.dp

private enum class RailPopover { Chats, Search }

@Composable
fun IconRail(
    state: SidebarState,
    actions: RailActions,
    modifier: Modifier = Modifier,
) {
    var popover by rememberSaveable { mutableStateOf<RailPopover?>(null) }
    val density = LocalDensity.current
    Box(
        modifier
            .width(RailWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .semantics { testTag = "icon_rail" },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(vertical = 10.dp),
        ) {
            // Wordmark shrinks to the glyph; tap expands the full drawer (§2.1).
            // The Column is fillMaxWidth so CenterHorizontally centers inside the
            // full 72dp rail — without it the column wraps to 48dp and hugs the
            // left edge (glyph/avatar/＋/icons all rendered off-center, left).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clickable { actions.expandDrawer() }
                    .semantics { testTag = "rail_glyph" },
            ) {
                Text(
                    "B",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // Search collapses to a ⌕ circle; the field opens as a popover over the rail.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .clickable { popover = RailPopover.Search }
                    .semantics { testTag = "rail_search" },
            ) {
                Text("⌕", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            RailItem("chats", "◌", state.activeRow == "chats", "Chats") { popover = RailPopover.Chats }
            if ("notes" !in state.hiddenRows) RailItem("notes", "▤", state.activeRow == "notes", "Notes") { actions.goto("notes") }
            if ("files" !in state.hiddenRows) RailItem("files", "▥", state.activeRow == "files", "Files") { actions.goto("files") }
            if ("workspace" !in state.hiddenRows) RailItem("workspace", "▦", state.activeRow == "workspace", "Workspace") { actions.goto("workspace") }
            if (state.memoriesGate && "memories" !in state.hiddenRows) RailItem("memories", "◍", state.activeRow == "memories", "Memories") { actions.goto("memories") }
            if (state.channelsGate && "channels" !in state.hiddenRows) RailItem("channels", "◈", state.activeRow == "channels", "Channels") { actions.goto("channels") }
            // Settings has NO rail item — it opens from the identity avatar.

            Spacer(Modifier.weight(1f))
            // Identity avatar stays at the bottom (§2.1); tap = Settings.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFFEFF3FE), CircleShape)
                    .clickable { actions.goto("settings") }
                    .semantics { testTag = "rail_settings" },
            ) {
                Text(
                    initialsOf(state.userName, state.userEmail),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3565F2),
                )
            }
            Spacer(Modifier.height(10.dp))
            // ＋ New chat becomes an icon-only circle; spawns in the thread pane in place.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { actions.newChat() }
                    .semantics { testTag = "rail_new_chat" },
            ) {
                Text("＋", fontSize = 18.sp, color = MaterialTheme.colorScheme.background, fontWeight = FontWeight.Bold)
            }
        }
        // hairline right edge (drawer border)
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
        // Anchored popovers (§2.1): recents under ◌, search under ⌕ — over the rail.
        if (popover != null) {
            Popup(
                alignment = Alignment.TopStart,
                offset = with(density) { IntOffset(RailWidth.roundToPx() + 8.dp.roundToPx(), 8.dp.roundToPx()) },
                onDismissRequest = { popover = null },
            ) {
                RailPopoverCard(
                    state = state,
                    actions = actions,
                    showSearch = popover == RailPopover.Search,
                    onDismiss = { popover = null },
                )
            }
        }
    }
}

/** One icon-only rail entry; long-press = label tooltip (§2.1). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RailItem(key: String, glyph: String, active: Boolean, label: String, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    RoundedCornerShape(14.dp),
                )
                .clickable { onClick() }
                .semantics { testTag = "rail_item_$key" },
        ) {
            Text(
                glyph,
                fontSize = 17.sp,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The rail popover body (§2.1): header (wordmark + expand-drawer affordance),
 * the search field when opened via ⌕, and the recents list — the SAME list
 * component data as the v3 drawer's chats section (no new screens, only new
 * containers).
 */
@Composable
private fun RailPopoverCard(
    state: SidebarState,
    actions: RailActions,
    showSearch: Boolean,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = Modifier
            .width(300.dp)
            .heightIn(max = 460.dp)
            .semantics { testTag = "rail_popover" },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Beacon",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // Popover header tap → expand the full v3 drawer over the content (§2.1).
                IconButton(onClick = { onDismiss(); actions.expandDrawer() }, modifier = Modifier.size(36.dp)) {
                    Text("☰", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showSearch) {
                OutlinedTextField(
                    value = state.search,
                    onValueChange = actions.search,
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    placeholder = { Text("Search chats", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Text("⌕", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(48.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
            val pinned = state.chats.filter { it.pinned && !it.archived }
            val rest = bucketChats(state.chats.filter { !it.pinned })
            LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                if (pinned.isNotEmpty()) {
                    item(key = "rp_pin") { SectionLabel("Pinned") }
                    items(pinned, key = { "rp_pin_" + it.chatId }) { chat ->
                        RailChatRow(chat, "✦", pinned = true, onOpen = { actions.openChat(chat.chatId); onDismiss() }, onMenu = { actions.chatActions(chat) })
                    }
                }
                rest.forEach { (label, chatsInBucket) ->
                    if (chatsInBucket.isNotEmpty()) {
                        item(key = "rp_$label") { SectionLabel(label) }
                        items(chatsInBucket, key = { "rp_" + label + it.chatId }) { chat ->
                            RailChatRow(chat, "◷", pinned = false, onOpen = { actions.openChat(chat.chatId); onDismiss() }, onMenu = { actions.chatActions(chat) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.5.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun RailChatRow(chat: ChatEntity, glyph: String, pinned: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            glyph,
            fontSize = 12.sp,
            color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
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
                .clickable { onMenu() }
                .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}
