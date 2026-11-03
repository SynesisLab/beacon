package dev.beacon.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.beacon.BuildConfig
import dev.beacon.core.data.AuthMethod
import dev.beacon.ui.theme.Paddings

/** M0 settings: server + auth summary, sign-out/clear. Headers/TLS editing widens in M4. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsViewModel.UiState,
    onSignOut: () -> Unit,
    onThemeMode: (dev.beacon.core.data.ThemeMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    androidx.compose.material3.Scaffold(
        // Scaffold + bar so the content respects the system insets — the earlier
        // bare Column rendered under the status bar (user report 2026-11-02).
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onClose) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        modifier = modifier.semantics { testTag = "settings" },
    ) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Paddings.screenEdge.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SettingSection("Server") {
            Text(state.serverUrl.ifBlank { "Not connected" })
            state.serverVersion?.let { Text("Version $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
        SettingSection("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    dev.beacon.core.data.ThemeMode.System to "System",
                    dev.beacon.core.data.ThemeMode.Light to "Light",
                    dev.beacon.core.data.ThemeMode.Dark to "Dark",
                ).forEach { (mode, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        label = { Text(label) },
                    )
                }
            }
        }
        HorizontalDivider()
        SettingSection("Authentication") {
            Text(
                when (state.authMethod) {
                    AuthMethod.None -> "Not signed in"
                    AuthMethod.ApiKey -> "API key (stored in Android Keystore)"
                    AuthMethod.Password -> "Email + password (token in Keystore)"
                },
            )
            state.signedInEmail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.customHeaders.isNotEmpty()) {
                Text(
                    "Custom headers: ${state.customHeaders.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        SettingSection("About") {
            Text("Beacon ${BuildConfig.VERSION_NAME}")
            Text(
                "A native client for your own Open WebUI instance. No provider credentials, no analytics.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = { confirming = true },
            enabled = state.authMethod != AuthMethod.None && !state.signingOut,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign out and clear keys") }

        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    } // Scaffold content

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Sign out?") },
            text = { Text("Removes the stored API key/token and server settings from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onSignOut()
                    },
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}