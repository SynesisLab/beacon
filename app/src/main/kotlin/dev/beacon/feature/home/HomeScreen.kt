package dev.beacon.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.beacon.ui.theme.Paddings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeViewModel.UiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Beacon") },
                actions = {
                    IconButton(onClick = actions.refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh models")
                    }
                    IconButton(onClick = actions.openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        modifier = modifier.semantics { testTag = "home" },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = Paddings.screenEdge.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(state.serverUrl, style = MaterialTheme.typography.titleMedium)
                    state.signedInEmail?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge)
                    }
                    state.serverVersion?.let {
                        Text(
                            "Server version $it",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                }
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            }
            Text("Models", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.models, key = { it.id }) { model ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = state.selectedModelId == model.id,
                            onCheckedChange = { actions.chooseModel(model.id) },
                        )
                        Text(model.name, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

data class HomeActions(
    val refresh: () -> Unit,
    val openSettings: () -> Unit,
    val chooseModel: (String) -> Unit,
)