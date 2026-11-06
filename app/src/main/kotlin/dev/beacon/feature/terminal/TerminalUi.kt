package dev.beacon.feature.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
/** Terminal FILES page, Conduit parity (original code). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    vm: TerminalViewModel,
    onBack: () -> Unit,
    onSaveDownload: (ByteArray, String) -> Unit,
    /** Hosted-in-tab header (v2 Files tab = the TabHeader chrome); null = the default back bar. */
    header: (@Composable () -> Unit)? = null,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            header?.invoke() ?: TopAppBar(
                title = { Text("Terminal files", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.openDialog("newfolder") }) {
                Icon(Icons.Filled.Add, "New folder")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PathCard(ui.cwd) { ui.serverId?.let { vm.goUp(it) } }
            when {
                ui.busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ui.error != null -> Text(ui.error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                else -> FilesCard(
                    entries = ui.entries,
                    onOpen = vm::openEntry,
                    onPreview = { e -> ui.serverId?.let { vm.previewFile(it, e) } },
                    onDownload = { e -> ui.serverId?.let { vm.download(it, e, onSaveDownload) } },
                    onRename = { e -> vm.openDialog("rename", e) },
                    onDelete = { e -> ui.serverId?.let { vm.delete(it, e) } },
                )
            }
        }
    }
    ScreenDialogs(vm, ui)
}

@Composable
private fun PathCard(cwd: String, onUp: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            IconButton(onClick = onUp) { Icon(androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_up), "Up") }
            Column {
                Text("Current path", style = MaterialTheme.typography.titleMedium)
                Text(cwd, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun FilesCard(
    entries: List<dev.beacon.core.api.TsFileEntry>,
    onOpen: (dev.beacon.core.api.TsFileEntry) -> Unit,
    onPreview: (dev.beacon.core.api.TsFileEntry) -> Unit,
    onDownload: (dev.beacon.core.api.TsFileEntry) -> Unit,
    onRename: (dev.beacon.core.api.TsFileEntry) -> Unit,
    onDelete: (dev.beacon.core.api.TsFileEntry) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (entries.isEmpty()) {
            Text("Empty folder", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                itemsIndexed(entries, key = { _, e -> "${e.type}:${e.name}" }) { _, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(entry) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            androidx.compose.ui.res.painterResource(
                                if (entry.isDirectory) dev.beacon.R.drawable.ic_tab_folder
                                else dev.beacon.R.drawable.ic_tab_notes
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            entry.subtitle().let {
                                if (it.isNotEmpty()) Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        RowMenu(entry, onPreview, onDownload, onRename, onDelete)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RowMenu(
    entry: dev.beacon.core.api.TsFileEntry,
    onPreview: (dev.beacon.core.api.TsFileEntry) -> Unit,
    onDownload: (dev.beacon.core.api.TsFileEntry) -> Unit,
    onRename: (dev.beacon.core.api.TsFileEntry) -> Unit,
    onDelete: (dev.beacon.core.api.TsFileEntry) -> Unit,
) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }) { Text("⋯", style = MaterialTheme.typography.titleMedium) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Preview") }, onClick = { open = false; onPreview(entry) })
            DropdownMenuItem(text = { Text("Download") }, onClick = { open = false; onDownload(entry) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename(entry) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete(entry) })
        }
    }
}

@Composable
private fun ScreenDialogs(vm: TerminalViewModel, ui: TerminalUi) {
    when (ui.dialog) {
        "newfolder" -> NameDialog("New folder", "", "Save", { vm.closeDialog() }) { name ->
            ui.serverId?.let { vm.createFolder(it, name) }
            vm.closeDialog()
        }
        "rename" -> NameDialog("Rename", ui.renameFor?.name ?: "", "Save", { vm.closeDialog() }) { name ->
            val entry = ui.renameFor
            if (entry != null && name.isNotBlank()) ui.serverId?.let { vm.rename(it, entry, name) }
            vm.closeDialog()
        }
    }
    ui.previewTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { vm.dismissPreview() },
            title = { Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            text = {
                Text(
                    ui.previewText ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = { TextButton(onClick = { vm.dismissPreview() }) { Text("Close") } },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
