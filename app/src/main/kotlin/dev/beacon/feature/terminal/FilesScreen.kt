package dev.beacon.feature.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.FilesApi
import dev.beacon.core.api.TerminalsApi
import dev.beacon.core.api.TsFileEntry
import dev.beacon.core.api.WorkspaceApis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The v3 Files surface (mockup 08, feature-map §10.6): ONE list with source
 * chips — "Uploads" (/api/v1/files/) + one chip per terminal server
 * (/api/v1/terminals/). Terminal source: mono breadcrumb (cwd, ⌃ up, ↻),
 * folders-first rows, file rows open the FileViewer (instance or terminal).
 * The Upload FAB follows the active source.
 */
data class FilesUi(
    val busy: Boolean = true,
    val error: String? = null,
    /** "uploads" or a terminal server id. */
    val source: String = "uploads",
    val servers: List<dev.beacon.core.api.TerminalServer> = emptyList(),
    val uploads: List<dev.beacon.core.api.FileRow> = emptyList(),
    // terminal leg
    val serverId: String? = null,
    val cwd: String = "/",
    val entries: List<TsFileEntry> = emptyList(),
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val terminals: TerminalsApi,
    private val workspace: WorkspaceApis,
    private val filesApi: FilesApi,
) : ViewModel() {

    private val _ui = MutableStateFlow(FilesUi())
    val ui: StateFlow<FilesUi> = _ui

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true, error = null)
        try {
            val servers = runCatching { terminals.servers() }.getOrDefault(emptyList())
            val uploads = runCatching { workspace.files() }.getOrDefault(emptyList())
            val cur = _ui.value
            val source = if (cur.source == "uploads" || cur.source !in servers.map { it.id }) {
                if (cur.source == "uploads" || servers.isEmpty()) "uploads" else servers.first().id
            } else cur.source
            _ui.value = cur.copy(
                busy = false,
                servers = servers,
                uploads = uploads.sortedByDescending { it.created_at ?: 0 },
                source = source,
            )
            if (source != "uploads") loadDirectory(source, null)
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, error = e.message)
        }
    }

    fun selectSource(source: String) {
        _ui.value = _ui.value.copy(source = source)
        if (source != "uploads") loadDirectory(source, null) else refreshUploads()
    }

    private fun refreshUploads() = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true, error = null)
        _ui.value = _ui.value.copy(
            busy = false,
            uploads = runCatching { workspace.files() }.getOrDefault(_ui.value.uploads)
                .sortedByDescending { it.created_at ?: 0 },
        )
    }

    /** Terminal leg: load [dir] (null = the server's cwd). Single-slash discipline. */
    fun loadDirectory(serverId: String, dir: String?) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true, error = null, serverId = serverId)
        try {
            val clean = (dir ?: terminals.cwd(serverId) ?: "/").trim().replace('\\', '/')
                .let { var v = it; while (v.contains("//")) v = v.replace("//", "/"); v.ifBlank { "/" } }
            _ui.value = _ui.value.copy(
                busy = false,
                cwd = clean,
                entries = terminals.list(serverId, clean).sortedWith(
                    compareBy({ !it.isDirectory }, { it.name.lowercase() }),
                ),
            )
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, error = e.message)
        }
    }

    fun goUp() {
        val id = _ui.value.serverId ?: return
        val up = _ui.value.cwd.trimEnd('/').substringBeforeLast('/')
        loadDirectory(id, if (up.isEmpty()) "/" else "$up/")
    }

    fun openEntry(entry: TsFileEntry, onOpenTerminalFile: (serverId: String, path: String, name: String) -> Unit) {
        val id = _ui.value.serverId ?: return
        val path = joinPath(_ui.value.cwd, entry.name, entry.isDirectory)
        if (entry.isDirectory) loadDirectory(id, path) else onOpenTerminalFile(id, path, entry.name)
    }

    fun uploadToActiveSource(fileName: String, bytes: ByteArray) = viewModelScope.launch {
        val cur = _ui.value
        if (cur.source == "uploads") {
            filesApi.upload(fileName, "application/octet-stream", bytes, process = false)
            refreshUploads()
        } else {
            cur.serverId?.let { terminals.upload(it, cur.cwd, fileName, bytes) }
            loadDirectory(cur.source, cur.cwd)
        }
    }

    /** Save a file to the user's storage (SAF hand-off done by the host). */
    private val _pendingSave = MutableStateFlow<Pair<ByteArray, String>?>(null)
    val pendingSave: StateFlow<Pair<ByteArray, String>?> = _pendingSave

    fun downloadUpload(fileId: String, name: String) = viewModelScope.launch {
        runCatching {
            filesApi.content(fileId).use { resp ->
                if (resp.isSuccessful) resp.body?.bytes()?.let { _pendingSave.value = it to name }
            }
        }
    }

    fun downloadTerminal(serverId: String, path: String, name: String) = viewModelScope.launch {
        runCatching {
            terminals.view(serverId, path)?.let { (n, read) ->
                (read as? dev.beacon.core.api.TsRead.Raw)?.let { _pendingSave.value = it.bytes to n }
            }
        }
    }

    fun clearPendingSave() { _pendingSave.value = null }

    private fun joinPath(dir: String, name: String, isDir: Boolean = false): String {
        val base = dir.trimEnd('/')
        val joined = "$base/$name"
        return if (isDir) "$joined/" else joined
    }
}

@Composable
fun FilesScreen(
    vm: FilesViewModel,
    onOpenInstanceFile: (fileId: String, name: String, mime: String) -> Unit,
    onOpenTerminalFile: (serverId: String, path: String, name: String) -> Unit,
    onPickUpload: () -> Unit,
    onSaveDownload: (ByteArray, String) -> Unit,
    header: @Composable () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = { header() },
        floatingActionButton = {
            FloatingActionButton(onClick = onPickUpload) {
                Icon(Icons.Filled.Add, "Upload")
                Text(" Upload", modifier = Modifier.padding(start = 8.dp))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Source chips (mockup 08): Uploads + one per terminal server.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                SourceChip("Uploads", "◍", ui.source == "uploads") { vm.selectSource("uploads") }
                ui.servers.forEach { s ->
                    SourceChip(s.name.ifBlank { s.id.take(8) }, "●", ui.source == s.id) { vm.selectSource(s.id) }
                }
            }
            when {
                ui.busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ui.error != null -> Text(ui.error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                ui.source == "uploads" -> UploadsList(
                    uploads = ui.uploads,
                    onOpen = { row -> onOpenInstanceFile(row.id, row.filename, row.meta?.content_type ?: "application/octet-stream") },
                    onSave = { row -> vm.downloadUpload(row.id, row.filename) },
                    onCopyPath = { row -> clipboard.setText(AnnotatedString("/api/v1/files/${row.id}/content")) },
                )
                else -> TerminalBody(
                    ui = ui,
                    onUp = vm::goUp,
                    onRefresh = { ui.serverId?.let { vm.loadDirectory(it, ui.cwd) } },
                    onOpen = { entry -> vm.openEntry(entry, onOpenTerminalFile) },
                    onSave = { entry ->
                        val path = (ui.cwd.trimEnd('/') + "/" + entry.name)
                        ui.serverId?.let { vm.downloadTerminal(it, path, entry.name) }
                    },
                    onCopyPath = { entry -> clipboard.setText(AnnotatedString((ui.cwd.trimEnd('/') + "/" + entry.name))) },
                )
            }
        }
    }

    // SAF save hand-off when a download landed (the host's SAF callback clears).
    val pending by vm.pendingSave.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        pending?.let { (bytes, name) -> onSaveDownload(bytes, name) }
    }
}

@Composable
private fun SourceChip(label: String, glyph: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$glyph $label", maxLines = 1) },
    )
}

@Composable
private fun UploadsList(
    uploads: List<dev.beacon.core.api.FileRow>,
    onOpen: (dev.beacon.core.api.FileRow) -> Unit,
    onSave: (dev.beacon.core.api.FileRow) -> Unit,
    onCopyPath: (dev.beacon.core.api.FileRow) -> Unit,
) {
    if (uploads.isEmpty()) {
        Text("No uploads yet — the ＋ Upload button adds files.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn {
        items(uploads, key = { it.id }) { row ->
            var menu by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(row) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("▤", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(row.filename, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    val size = row.meta?.size?.let { "%.1f KB".format(it / 1024.0) } ?: ""
                    if (size.isNotEmpty()) Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Text("⋯", style = MaterialTheme.typography.titleMedium) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Save…") }, onClick = { menu = false; onSave(row) })
                        DropdownMenuItem(text = { Text("Copy path") }, onClick = { menu = false; onCopyPath(row) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalBody(
    ui: FilesUi,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (TsFileEntry) -> Unit,
    onSave: (TsFileEntry) -> Unit,
    onCopyPath: (TsFileEntry) -> Unit,
) {
    // Mono breadcrumb (mockup 08): host / cwd, ⌃ up, ↻ refresh.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val serverName = ui.servers.firstOrNull { it.id == ui.serverId }?.name?.ifBlank { null } ?: ui.serverId ?: ""
        Text(
            text = "$serverName / ${ui.cwd.removePrefix("/")}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        IconButton(onClick = onUp, modifier = Modifier.size(28.dp)) {
            Icon(androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_up), "Up", modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Refresh, "Refresh", modifier = Modifier.size(16.dp))
        }
    }
    if (ui.entries.isEmpty()) {
        Text("Empty folder", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn {
        items(ui.entries, key = { "${it.type}:${it.name}" }) { entry ->
            var menu by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(entry) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(if (entry.isDirectory) "▸" else "▤", color = MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                    val sub = entry.subtitle()
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Text("⋯", style = MaterialTheme.typography.titleMedium) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!entry.isDirectory) DropdownMenuItem(text = { Text("Save…") }, onClick = { menu = false; onSave(entry) })
                        DropdownMenuItem(text = { Text("Copy path") }, onClick = { menu = false; onCopyPath(entry) })
                    }
                }
            }
        }
    }
}
