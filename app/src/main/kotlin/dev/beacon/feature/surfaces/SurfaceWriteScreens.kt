package dev.beacon.feature.surfaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.ChannelMessageRow
import dev.beacon.core.api.WorkspaceApis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * M4-d2: the write-flow surfaces (user request: "folders, notes, channels…
 * don't work — complete them") — folders create/rename/delete + the folder's
 * chats; notes create/read/write (TipTap doc round-trip); channel read/post.
 */
@HiltViewModel
class SurfaceWriteViewModel @Inject constructor(
    private val apis: WorkspaceApis,
) : ViewModel() {

    data class UiState(
        val busy: Boolean = false,
        val error: String? = null,
        val folderChats: List<dev.beacon.core.api.dto.ChatPayload> = emptyList(),
        val channelMessages: List<ChannelMessageRow> = emptyList(),
        val note: Pair<String, String>? = null, // title to plain content
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private fun set(reducer: UiState.() -> UiState) {
        _state.value = _state.value.reducer()
    }

    // ── folders ─────────────────────────────────────────────────────────────

    fun createFolder(name: String, onDone: () -> Unit) {
        async { apis.createFolder(name) }.invokeOnCompletion { onDone() }
    }

    fun deleteFolder(folderId: String, onDone: () -> Unit) {
        async { apis.deleteFolder(folderId) }.invokeOnCompletion { onDone() }
    }

    fun renameFolder(folderId: String, name: String, onDone: () -> Unit) {
        async { apis.renameFolder(folderId, name) }.invokeOnCompletion { onDone() }
    }

    fun folderChats(folderId: String) {
        viewModelScope.launch {
            set { copy(busy = true, error = null) }
            try {
                val chats = withTimeoutSafe { apis.folderChats(folderId) }
                set { copy(busy = false, folderChats = chats ?: emptyList()) }
            } catch (e: Exception) {
                set { copy(busy = false, error = e.message) }
            }
        }
    }

    // ── notes ────────────────────────────────────────────────────────────────

    fun loadNote(noteId: String) {
        viewModelScope.launch {
            set { copy(busy = true, error = null) }
            try {
                val loaded = withTimeoutSafe { apis.note(noteId) }
                set { copy(busy = false, note = loaded) }
            } catch (e: Exception) {
                set { copy(busy = false, error = e.message) }
            }
        }
    }

    fun saveNote(noteId: String, title: String, content: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            set { copy(busy = true) }
            val ok = runCatching { apis.updateNote(noteId, title, content) }.getOrDefault(false)
            set { copy(busy = false, error = if (ok) null else "Saving failed") }
            if (ok) onSaved()
        }
    }

    fun createNote(title: String, content: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val id = runCatching { apis.createNote(title, content) }.getOrNull()
            onDone(id)
        }
    }

    // ── channels ─────────────────────────────────────────────────────────────

    fun channelMessages(channelId: String) {
        viewModelScope.launch {
            set { copy(busy = true, error = null) }
            try {
                val messages = withTimeoutSafe { apis.channelMessages(channelId) }
                set { copy(busy = false, channelMessages = messages ?: emptyList()) }
            } catch (e: Exception) {
                set { copy(busy = false, error = e.message) }
            }
        }
    }

    fun postChannelMessage(channelId: String, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            set { copy(busy = true) }
            val tempId = java.util.UUID.randomUUID().toString()
            val ok = runCatching { apis.postChannelMessage(channelId, content, tempId) }.getOrDefault(false)
            set { copy(busy = false, error = if (ok) null else "Posting failed") }
            if (ok) channelMessages(channelId)
        }
    }

    private fun async(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            set { copy(error = e.message) }
        }
    }

    private suspend fun <T> withTimeoutSafe(block: suspend () -> T): T? = try {
        kotlinx.coroutines.withTimeout(15_000) { block() }
    } catch (_: Exception) {
        null
    }
}

// ── Surface screens (compose) ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    writeModel: SurfaceWriteViewModel,
    loaderState: LoaderState,
    onBack: () -> Unit,
    onOpenFolderChats: (String) -> Unit,
    /** Re-fetch the listing after a mutation (M4 fix: the list went stale). */
    onChanged: () -> Unit = {},
) {
    val state by writeModel.state.collectAsStateWithLifecycle()
    var createOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<String, String>?>(null) } // id to name

    Scaffold(
        topBar = { SurfaceTopBar("Folders", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { createOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New folder")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().fillMaxWidth()) {
            if (loaderState.folders.isEmpty() && !loaderState.loading) {
                HintRow("No folders yet — create one with +.")
            }
            LazyColumn {
                items(loaderState.folders, key = { it.id }) { folder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFolderChats(folder.id) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, style = MaterialTheme.typography.titleMedium)
                            if (folder.unread_count > 0) {
                                Text(
                                    "${folder.unread_count} new",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        TextButton(onClick = { menuFor = folder.id to folder.name }) { Text("Rename") }
                        TextButton(onClick = { writeModel.deleteFolder(folder.id) { onChanged() } }) { Text("Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (createOpen || menuFor != null) {
        val existingName = menuFor?.second.orEmpty()
        NameDialog(
            title = if (createOpen) "New folder" else "Rename folder",
            initial = existingName,
            confirmLabel = "Save",
            onDismiss = { createOpen = false; menuFor = null },
            onConfirm = { value ->
                if (createOpen) {
                    writeModel.createFolder(value) { createOpen = false; onChanged() }
                } else {
                    menuFor?.let { (id, _) -> writeModel.renameFolder(id, value) { menuFor = null; onChanged() } }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderChatsScreen(
    writeModel: SurfaceWriteViewModel,
    folderName: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val state by writeModel.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // the chat's folder id arrives via the route's call
    }
    Scaffold(
        topBar = { SurfaceTopBar(folderName.ifBlank { "Folder" }, onBack) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            LazyColumn {
                items(
                    state.folderChats,
                    key = { it.id },
                ) { chat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(chat.id) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Column {
                            Text(chat.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (state.folderChats.isEmpty() && !state.busy) {
                HintRow("No chats in this folder.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    writeModel: SurfaceWriteViewModel,
    noteId: String?,
    title: String,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onSaved: () -> Unit,
) {
    val state by writeModel.state.collectAsStateWithLifecycle()
    var titleDraft by remember(noteId) { mutableStateOf(title) }
    var contentDraft by remember(noteId) { mutableStateOf("") }
    var loaded by remember(noteId) { mutableStateOf(noteId == null) }

    androidx.compose.runtime.LaunchedEffect(noteId) {
        if (noteId != null) writeModel.loadNote(noteId)
    }
    androidx.compose.runtime.LaunchedEffect(state.note) {
        state.note?.let { (t, c) ->
            titleDraft = t
            contentDraft = c
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (noteId == null) "New note" else titleDraft.ifBlank { "Note" },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (noteId == null) {
                                writeModel.createNote(titleDraft.ifBlank { "Untitled" }, contentDraft) { id ->
                                    id?.let(onCreated) ?: onSaved()
                                }
                            } else {
                                writeModel.saveNote(noteId, titleDraft, contentDraft, onSaved)
                            }
                        },
                        enabled = !state.busy && loaded,
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().fillMaxWidth()) {
            OutlinedTextField(
                value = titleDraft,
                onValueChange = { titleDraft = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = contentDraft,
                onValueChange = { contentDraft = it },
                label = { Text("Content") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelChatScreen(
    writeModel: SurfaceWriteViewModel,
    channelId: String,
    channelName: String,
    onBack: () -> Unit,
) {
    val state by writeModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(channelId) {
        writeModel.channelMessages(channelId)
    }

    Scaffold(
        topBar = { SurfaceTopBar(channelName.ifBlank { "Channel" }, onBack) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            LazyColumn(
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(state.channelMessages, key = { it.id }) { message ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            message.content.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        writeModel.postChannelMessage(channelId, draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.busy,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurfaceTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
                    Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

@Composable
private fun HintRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
                    Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}