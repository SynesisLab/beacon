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
                _ui.value = _ui.value.copy(busy = false, error = "No terminal servers on this instance.")
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
        _ui.value = _ui.value.copy(busy = true, error = null)
        try {
            _ui.value = _ui.value.copy(
                busy = false,
                cwd = dir,
                entries = api.list(serverId, dir),
            )
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, error = e.message)
        }
    }

    fun goUp(serverId: String) {
        val up = _ui.value.cwd.trimEnd('/').substringBeforeLast('/')
        loadDirectory(serverId, if (up.isEmpty()) "/" else "$up/")
    }

    fun openEntry(entry: TsFileEntry) {
        val serverId = _ui.value.serverId ?: return
        if (entry.isDirectory) {
            loadDirectory(serverId, entry.path)
        } else {
            preview(serverId, entry)
        }
    }

    fun preview(serverId: String, entry: TsFileEntry) = viewModelScope.launch {
        val read = runCatching { api.read(serverId, entry.path) }.getOrNull()
        when (read) {
            is dev.beacon.core.api.TsRead.Text ->
                _ui.value = _ui.value.copy(previewTitle = entry.name, previewText = read.text)
            else ->
                _ui.value = _ui.value.copy(error = "This file isn't text; use Download.")
        }
    }

    fun dismissPreview() { _ui.value = _ui.value.copy(previewTitle = null, previewText = null) }

    fun download(serverId: String, entry: TsFileEntry, onSave: (ByteArray, String) -> Unit) =
        viewModelScope.launch {
            runCatching { api.view(serverId, entry.path) }.getOrNull()?.let { (name, read) ->
                (read as? dev.beacon.core.api.TsRead.Raw)?.let { onSave(it.bytes, name) }
            }
        }

    fun createFolder(serverId: String, name: String) = viewModelScope.launch {
        val dir = _ui.value.cwd.trimEnd('/')
        val path = ("$dir/$name").ifEmpty { "/$name" }
        runCatching { api.mkdir(serverId, path) }
        loadDirectory(serverId, _ui.value.cwd)
    }

    fun rename(serverId: String, entry: TsFileEntry, newName: String) = viewModelScope.launch {
        val parent = _ui.value.cwd.trimEnd('/')
        val slash = if (entry.isDirectory) "/" else ""
        runCatching { api.move(serverId, entry.path.trimEnd('/'), "$parent/$newName$slash") }
        loadDirectory(serverId, _ui.value.cwd)
    }

    fun delete(serverId: String, path: String) = viewModelScope.launch {
        runCatching { api.delete(serverId, path) }
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

/** Sizes in the human form; dates as the local short date. */
fun TsFileEntry.subtitle(): String {
    val size = when {
        size == null -> ""
        size < 1024 -> "$size B"
        size < 1 shl 20 -> "%.1f KB".format(size / 1024.0)
        else -> "%.1f MB".format(size / (1024.0 * 1024.0))
    }
    val date = modified_at?.let { java.time.Instant.ofEpochMilli(it).toString().take(10) }.orEmpty()
    return listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
}