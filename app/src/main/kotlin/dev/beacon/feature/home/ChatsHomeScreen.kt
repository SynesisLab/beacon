package dev.beacon.feature.home

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.feature.chat.LandingCritter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * Chats home (UI v2.0 §4.2 / mockup 06) — tab 1 and the START destination:
 * serif Chats header · search · ＋ = new folder · Pinned (coral ✦) → Folders
 * (name · meta · ⋯) → All chats date-grouped (Today / Yesterday / Previous
 * 7 / Earlier). One row-actions sheet; FAB black `+ New chat`. The drawer is
 * GONE: recents live exactly here, one back-press from any chat.
 *
 * State-driven (no VM inside): the host wires [ChatsHomeActions] — goldens
 * render this composable with a fixture [ChatsHomeViewModel.UiState].
 */
data class ChatsHomeActions(
    val search: (String) -> Unit = {},
    val openChat: (String) -> Unit = {},
    val openFolderChats: (String, String) -> Unit = { _, _ -> },
    val newChat: () -> Unit = {},
    val pinToggle: (String) -> Unit = {},
    val delete: (String) -> Unit = {},
    /** Resolves POST /chats/{id}/share and fires the chooser (host-side). */
    val shareLink: suspend (String) -> Unit = {},
    val createFolder: (String) -> Unit = {},
    val renameFolder: (String, String) -> Unit = { _, _ -> },
    val deleteFolder: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsHomeScreen(
    state: ChatsHomeViewModel.UiState,
    actions: ChatsHomeActions,
    /** The bottom tab bar, provided by the host (active = Chats). */
    tabState: @Composable () -> Unit,
) {
    var menuFolder by remember { mutableStateOf<Pair<String, String>?>(null) } // id to name
    var menuChat by remember { mutableStateOf<ChatEntity?>(null) }
    var dialogMode by remember { mutableStateOf<String?>(null) } // create | rename
    var folderIdDraft by remember { mutableStateOf("") }
    var folderNameDraft by remember { mutableStateOf("") }
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()

    val pinned = state.chats.filter { it.pinned && !it.archived }
    val buckets = remember(state.chats) { bucketChats(state.chats) }

    Scaffold(
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Text(
                    "Chats",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                )
                IconButton(onClick = {
                    // ＋ header = NEW FOLDER (§4.2); chats come from the FAB.
                    folderNameDraft = ""
                    dialogMode = "create"
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "New folder")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                // v2: black pill `+ New chat` (ink container, cream icon).
                onClick = actions.newChat,
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(50),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("New chat", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        bottomBar = tabState,
        modifier = Modifier.semantics { testTag = "chats_home" },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            LaunchedEffect(state.loading) { } // refresh() is the host's job

            SearchField(state.search, actions.search)

            if (state.chats.isEmpty() && state.folders.isEmpty() && !state.loading) {
                EmptyState()
            } else {
                LazyColumn(
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (pinned.isNotEmpty()) {
                        item(key = "sect_pinned") { SectionLabel("Pinned") }
                        items(pinned, key = { "pin_" + it.chatId }) { chat ->
                            ChatRow(chat, rowSubtitle(chat), star = true,
                                open = { actions.openChat(chat.chatId) },
                                menuOpen = { menuChat = chat })
                        }
                    }
                    if (state.folders.isNotEmpty()) {
                        item(key = "sect_folders") { SectionLabel("Folders") }
                        items(state.folders, key = { "fold_" + it.id }) { folder ->
                            FolderRowView(folder,
                                open = { actions.openFolderChats(folder.id, folder.name) },
                                menuOpen = { menuFolder = folder.id to folder.name })
                        }
                    }
                    listOf("Today", "Yesterday", "Previous 7 days", "Earlier").forEach { label ->
                        val chatsInBucket = buckets[label].orEmpty()
                        if (chatsInBucket.isNotEmpty()) {
                            item(key = "sect_$label") { SectionLabel(label) }
                            items(chatsInBucket, key = { it.chatId }) { chat ->
                                ChatRow(chat, rowSubtitle(chat), star = false,
                                    open = { actions.openChat(chat.chatId) },
                                    menuOpen = { menuChat = chat })
                            }
                        }
                    }
                    state.error?.let { err ->
                        item(key = "home_error") {
                            Text(
                                err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── row-actions sheet (one for every chat ⋯) ─────────────────────────────
    menuChat?.let { chat ->
        ModalBottomSheet(onDismissRequest = { menuChat = null }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SheetTitle(chat.title.ifBlank { "Chat" })
                SheetRow(if (chat.pinned) "Unpin" else "Pin") {
                    menuChat = null
                    actions.pinToggle(chat.chatId)
                }
                SheetRow("Share link") {
                    menuChat = null
                    shareScope.launch { actions.shareLink(chat.chatId) }
                }
                SheetRow("Delete") {
                    menuChat = null
                    actions.delete(chat.chatId)
                }
            }
        }
    }
    menuFolder?.let { (folderId, name) ->
        ModalBottomSheet(onDismissRequest = { menuFolder = null }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SheetTitle(name)
                SheetRow("Open") { menuFolder = null; actions.openFolderChats(folderId, name) }
                SheetRow("Rename") {
                    menuFolder = null
                    folderIdDraft = folderId
                    folderNameDraft = name
                    dialogMode = "rename"
                }
                SheetRow("Delete") {
                    menuFolder = null
                    actions.deleteFolder(folderId)
                }
            }
        }
    }
    dialogMode?.let { mode ->
        NameSheetDialog(
            title = if (mode == "create") "New folder" else "Rename folder",
            initial = folderNameDraft,
            confirmLabel = if (mode == "create") "Create" else "Rename",
            onDismiss = { dialogMode = null },
            onConfirm = { name ->
                if (mode == "create") {
                    actions.createFolder(name)
                } else {
                    actions.renameFolder(folderIdDraft, name)
                }
                dialogMode = null
            },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        shape = RoundedCornerShape(50),
        placeholder = { Text("Search chats", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(54.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 11.sp,
            letterSpacing = 0.8.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** Chat row: star marker (coral ✦ pinned / faint ◌), title · time · ⋯. */
@Composable
private fun ChatRow(
    chat: ChatEntity,
    subtitle: String,
    star: Boolean,
    open: () -> Unit,
    menuOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open() }
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Text(
            if (star) "✦" else "◌",
            color = if (chat.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                chat.title.ifBlank { "Untitled chat" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = menuOpen) { Text("⋯", style = MaterialTheme.typography.titleMedium) }
    }
}

/** Folder row: ▸ name · meta · ⋯. */
@Composable
private fun FolderRowView(
    folder: dev.beacon.core.api.FolderRow,
    open: () -> Unit,
    menuOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open() }
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.width(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (folder.unread_count > 0) "${folder.unread_count} new" else "folder",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = menuOpen) { Text("⋯", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
    ) {
        LandingCritter(size = 84.dp)
        Text(
            "No chats yet — start a conversation",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SheetRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 13.dp),
    )
}

/** The new/rename-folder dialog (create + rename share one sheet). */
@Composable
private fun NameSheetDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── pure helpers (v2 §4.2 grouping; epoch-ms server truth) ───────────────────

internal fun chatDateBucket(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return "Earlier"
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    val day = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (day.isAfter(today.minusDays(7))) "Previous 7 days" else "Earlier"
    }
}

internal fun bucketChats(chats: List<ChatEntity>): Map<String, List<ChatEntity>> {
    val sorted = chats.filter { !it.archived }.sortedByDescending { it.updatedAt ?: 0L }
    val buckets = linkedMapOf(
        "Today" to mutableListOf<ChatEntity>(),
        "Yesterday" to mutableListOf<ChatEntity>(),
        "Previous 7 days" to mutableListOf<ChatEntity>(),
        "Earlier" to mutableListOf<ChatEntity>(),
    )
    sorted.forEach { chat -> buckets.getOrPut(chatDateBucket(chat.updatedAt)) { mutableListOf() }.add(chat) }
    return buckets.mapValues { it.value.toList() }
}

/** Row subtitle: time-of-day today / "yesterday" / short date. */
internal fun rowSubtitle(chat: ChatEntity): String {
    val ms = chat.updatedAt ?: return ""
    val zone = ZoneId.systemDefault()
    return when (chatDateBucket(ms)) {
        "Today" -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(
            java.time.LocalDateTime.ofInstant(Instant.now(), zone),
        )
        "Yesterday" -> "yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).format(
            Instant.ofEpochMilli(ms).atZone(zone),
        )
    }
}
