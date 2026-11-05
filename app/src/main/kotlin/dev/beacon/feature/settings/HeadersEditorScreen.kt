package dev.beacon.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.beacon.ui.theme.Paddings

/**
 * M4 advanced auth: name/value rows for headers attached to every request sent
 * to the instance only (org-routing, gateway keys, pasted reverse-proxy Cookie).
 * Values are credentials — they live in SecureStore (Keystore), never in the
 * settings store, and are never logged.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HeadersEditorScreen(
    state: HeadersViewModel.UiState,
    onSave: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (Long) -> Unit,
    onName: (Long, String) -> Unit,
    onValue: (Long, String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Save persists the whole blob; pop only after the write lands (saved=true
    // also covers the empty-map → alias-delete path).
    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom headers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = onSave) { Text("Save") }
                },
            )
        },
        modifier = modifier.semantics { testTag = "headers-editor" },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Paddings.screenEdge.dp, vertical = 12.dp),
        ) {
            Text(
                "Sent only to your Open WebUI instance — org-routing, gateway keys." +
                    " A pasted Cookie header covers reverse-proxy sign-ins.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.drafts.isEmpty()) {
                Text(
                    "None yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                state.drafts.forEach { draft ->
                    val error = state.errors[draft.id]
                    Column(modifier = Modifier.padding(top = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = draft.name,
                                onValueChange = { onName(draft.id, it) },
                                label = { Text("Header name") },
                                singleLine = true,
                                isError = error != null,
                                supportingText = error?.let { { Text(it) } },
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRemove(draft.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Remove header")
                            }
                        }
                        OutlinedTextField(
                            value = draft.value,
                            onValueChange = { onValue(draft.id, it) },
                            label = { Text("Value") },
                            singleLine = true,
                            isError = error != null,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) { Text("Add header") }

            Text(
                "Authorization is managed by your Beacon sign-in and cannot be overridden." +
                    " Sign-out clears every header on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
        }
    }
}
