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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ── M4 fix: Workspace as REAL sections (prompts/tools/knowledge rows), not a
// prompts-only list with bullet header.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    onPrompt: (dev.beacon.core.api.PromptRow) -> Unit,
) {
    val vm: SurfaceViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspace", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading && state.prompts.isEmpty()) {
                item { SectionHeader("Loading…") }
            }
            if (state.prompts.isNotEmpty()) {
                item { SectionHeader("Prompts") }
                items(state.prompts, key = { it.command }) { prompt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPrompt(prompt) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Column {
                            Text(prompt.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "/${prompt.command}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (state.tools.isNotEmpty()) {
                item { SectionHeader("Tools") }
                items(state.tools, key = { it.id }) { tool ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(tool.name, style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider()
                }
            }
            if (state.knowledge.isNotEmpty()) {
                item { SectionHeader("Knowledge") }
                items(state.knowledge, key = { it.id }) { knowledge ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Column {
                            Text(knowledge.name, style = MaterialTheme.typography.titleMedium)
                            knowledge.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (state.prompts.isEmpty() && state.tools.isEmpty() && state.knowledge.isEmpty() && !state.loading) {
                item {
                    Text(
                        "No workspace items on this server yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/**
 * Shared read-only listing screen for the drawer surfaces (M4-d1). Tap
 * callbacks are per-surface; content stays server-owned (no local writes yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ListSurfaceScreen(
    title: String,
    items: List<T>,
    loading: Boolean,
    error: String?,
    rowKey: (T) -> String,
    onBack: () -> Unit,
    onItem: (T) -> Unit,
    /** Optional create-action FAB (M4-d2: notes/folders). */
    fab: (@Composable () -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    Scaffold(
        floatingActionButton = { fab?.invoke() },
        topBar = {
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
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            header?.let { it() }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(16.dp),
                )
                items.isEmpty() -> Text(
                    "Nothing here yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn {
                    items(items, key = rowKey) { item ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onItem(item) },
                        ) { row(item) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}