package dev.beacon.feature.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.Alignment
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.TerminalsApi
import dev.beacon.core.api.TsFileEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Conduit's terminal-FILES page, copied function-for-function (original
 * implementation; GPL read): the current-path card with go-up, the listing
 * card with file/folder rows + per-row menus (preview / download / rename /
 * delete), create-folder FAB, and the SAF upload. Terminal servers ride the
 * OWUI proxy (/api/v1/terminals/…).
 */
data class TerminalUi(
    val busy: Boolean = true,
    val error: String? = null,
    val servers: List<dev.beacon.core.api.TerminalServer> = emptyList(),
    val serverId: String? = null,
    val cwd: String = "/",
    val entries: List<TsFileEntry> = emptyList(),
    val previewTitle: String? = null,
    val previewText: String? = null,
    val dialog: String = "", // none | newfolder | rename
    val renameFor: TsFileEntry? = null,
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val api: TerminalsApi,
) : ViewModel() {
    private val _ui = MutableStateFlow(TerminalUi())
    var ui: StateFlow<TerminalUi> = _ui

    init { bootstrap() }

    fun bootstrap() = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true, error = null)
        try {
            val list = api.servers()
            if (list.isEmpty()) {
                // terminals.py:87-106 returns [] when ENABLE_TOOL_SERVERS is
                // off, no terminal_server.connections exist, or the user's
                // groups lack connection access — name the admin lever.
                _ui.value = _ui.value.copy(
                    busy = false,
                    error = "No terminal servers are exposed to your account. " +
                        "An admin adds them on the server (Admin panel → Terminal connections, with tool servers enabled).",
                )
                return@launch
            }
            val first = list.first()
            val dir = api.cwd(first.id) ?: "/"
            loadDirectory(first.id, dir)
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, error = e.message)
        }
    }

    fun loadDirectory(serverId: String, dir: String) = viewModelScope.launch {
        // Conduit's normalizeTerminalPath parity: backslashes → slashes,
        // collapse runs, blank = root.
        val clean = run {
            var v = dir.trim().replace('\\', '/')
            while (v.contains("//")) v = v.replace("//", "/")
            v.ifBlank { "/" }
        }
        _ui.value = _ui.value.copy(busy = true, error = null)
        try {
            _ui.value = _ui.value.copy(
                busy = false,
                // RECORD the active server: the row/menu handlers read it
                // from state (2026-11-03 device log: it stayed null after
                // load — the whole interactive layer silently no-op'd).
                serverId = serverId,
                cwd = clean,
                entries = api.list(serverId, clean),
            )
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, error = e.message)
        }
    }

    fun goUp(serverId: String) {
        val up = _ui.value.cwd.trimEnd('/').substringBeforeLast('/')
        loadDirectory(serverId, if (up.isEmpty()) "/" else "$up/")
    }

    /**
     * Entries carry only a name (+type) — full paths compose from the cwd
     * (TerminalFile has NO path field; web parity, index.ts:34-40).
     */
    private fun pathFor(entry: TsFileEntry): String =
        joinPath(_ui.value.cwd, entry.name, entry.isDirectory)

    private fun joinPath(dir: String, name: String, isDir: Boolean = false): String {
        val base = dir.trimEnd('/')
        val joined = "$base/$name"
        return if (isDir) "$joined/" else joined
    }

    fun openEntry(entry: TsFileEntry) {
        val serverId = _ui.value.serverId ?: return
        if (entry.isDirectory) {
            loadDirectory(serverId, pathFor(entry))
        } else {
            preview(serverId, pathFor(entry) to entry.name)
        }
    }

    fun preview(serverId: String, ref: Pair<String, String>) = viewModelScope.launch {
        val (path, name) = ref
        val read = runCatching { api.read(serverId, path) }.getOrNull()
        when (read) {
            is dev.beacon.core.api.TsRead.Text ->
                _ui.value = _ui.value.copy(previewTitle = name, previewText = read.text)
            else ->
                _ui.value = _ui.value.copy(error = "This file isn't text; use Download.")
        }
    }

    /** Row-menu Preview: the path composes here (entries are cwd-relative). */
    fun previewFile(serverId: String, entry: TsFileEntry) =
        preview(serverId, pathFor(entry) to entry.name)

    fun dismissPreview() { _ui.value = _ui.value.copy(previewTitle = null, previewText = null) }

    fun download(serverId: String, entry: TsFileEntry, onSave: (ByteArray, String) -> Unit) =
        viewModelScope.launch {
            runCatching { api.view(serverId, pathFor(entry)) }.getOrNull()?.let { (name, read) ->
                (read as? dev.beacon.core.api.TsRead.Raw)?.let { onSave(it.bytes, name) }
            }
        }

    fun createFolder(serverId: String, name: String) = viewModelScope.launch {
        runCatching { api.mkdir(serverId, joinPath(_ui.value.cwd, name)) }
        loadDirectory(serverId, _ui.value.cwd)
    }

    fun rename(serverId: String, entry: TsFileEntry, newName: String) = viewModelScope.launch {
        val slash = if (entry.isDirectory) "/" else ""
        runCatching {
            api.move(serverId, pathFor(entry).trimEnd('/'), joinPath(_ui.value.cwd, newName) + slash)
        }
        loadDirectory(serverId, _ui.value.cwd)
    }

    fun delete(serverId: String, entry: TsFileEntry) = viewModelScope.launch {
        runCatching { api.delete(serverId, pathFor(entry)) }
        loadDirectory(serverId, _ui.value.cwd)
    }

    fun upload(serverId: String, fileName: String, bytes: ByteArray) = viewModelScope.launch {
        runCatching { api.upload(serverId, _ui.value.cwd, fileName, bytes) }
        loadDirectory(serverId, _ui.value.cwd)
    }

    fun markServer(serverId: String) { _ui.value = _ui.value.copy(serverId = serverId) }
    fun openDialog(kind: String, entry: TsFileEntry? = null) {
        _ui.value = _ui.value.copy(dialog = kind, renameFor = entry)
    }
    fun closeDialog() { _ui.value = _ui.value.copy(dialog = "none", renameFor = null) }

    /** The SAF CreateDocument hand-off when saving a download. */
    var pendingDownload: Pair<ByteArray, String>? = null
}

/** Sizes in the human form; dates as the local short date (epoch normalizer
 *  shares the §17 magnitude rule — upstream units uncertain, display-only). */
fun TsFileEntry.subtitle(): String {
    val size = when {
        size == null -> ""
        size < 1024L -> "$size B"
        size < 1 shl 20 -> "%.1f KB".format(size / 1024.0)
        else -> "%.1f MB".format(size / (1024.0 * 1024.0))
    }
    val ms = modified?.let { m ->
        // Live upstream sends fractional epoch SECONDS (1777343786.22…);
        // Conduit truncates num→seconds—magnitude rule keeps ms intact too.
        (if (m >= 100_000_000_000.0) m.toLong() else (m * 1_000.0).toLong())
    }
    val date = ms?.let {
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.SHORT)
            .format(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()))
    }.orEmpty()
    return listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
}