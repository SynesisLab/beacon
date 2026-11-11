package dev.beacon.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
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
    onOpenHeaders: () -> Unit = {},
    /** Sidebar row visibility ([rowKey], visible). */
    onNavRowVisible: (String, Boolean) -> Unit = { _, _ -> },
    /** App lock toggle (biometric/device-credential gate at app open). */
    onAppLock: (Boolean) -> Unit = {},
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
        // Mockup 09: grouped cards. Account → Appearance (mode preview cards) →
        // Sidebar toggles → Advanced → About caption.
        SettingCard {
            SettingRow(icon = "◈", title = { Text(state.serverUrl.ifBlank { "Not connected" }) },
                value = state.serverVersion?.let { "v$it" }, chevron = true)
            SettingRow(
                icon = "◉",
                title = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(state.signedInEmail ?: "Not signed in")
                        state.userRole?.let {
                            Text(
                                it.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                },
            )
            SettingRow(
                icon = "⇤",
                title = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
                subtitle = "wipes keys on-device only",
                danger = true,
                onClick = { confirming = true },
                enabled = state.authMethod != AuthMethod.None && !state.signingOut,
            )
        }
        SettingCard {
            SettingRow(icon = "◐", title = { Text("Color mode") })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                listOf(
                    dev.beacon.core.data.ThemeMode.Light to "Light",
                    dev.beacon.core.data.ThemeMode.Dark to "Dark",
                    dev.beacon.core.data.ThemeMode.System to "System",
                ).forEach { (mode, label) ->
                    val selected = state.themeMode == mode
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onThemeMode(mode) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                )
                                .background(
                                    when (mode) {
                                        dev.beacon.core.data.ThemeMode.Dark -> androidx.compose.ui.graphics.Color(0xFF232220)
                                        dev.beacon.core.data.ThemeMode.System -> androidx.compose.ui.graphics.Color(0xFF232220) // split preview approximated
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                )
                                .padding(10.dp),
                        ) {
                            Column {
                                Box(Modifier.fillMaxWidth(0.6f).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                                Spacer(Modifier.height(5.dp))
                                Box(Modifier.fillMaxWidth(0.4f).height(4.dp).background(MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                            }
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SettingCard {
            SettingRow(icon = "◼", title = { Text("App lock") }, subtitle = "verify it's you when the app opens")
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAppLock(!state.appLock) }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    if (state.appLock) "Biometric / device credential required" else "Off",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Switch(checked = state.appLock, onCheckedChange = onAppLock)
            }
        }
        SettingCard {
            SettingRow(icon = "▤", title = { Text("Sidebar") }, subtitle = "show or hide sidebar rows")
            listOf(
                "notes" to "Notes",
                "files" to "Files",
                "workspace" to "Workspace",
                "memories" to "Memories",
                "channels" to "Channels",
                // "settings" is not toggleable — the nav row never hides
                // (Settings opens from the identity bar above the list).
            ).forEach { (key, label) ->
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(label, Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = key !in state.hiddenNavRows,
                        onCheckedChange = { visible -> onNavRowVisible(key, visible) },
                    )
                }
            }
            Text(
                "Memories and Channels also need their server features enabled.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingCard {
            SettingRow(
                icon = "⚙",
                title = { Text("Custom HTTP headers") },
                subtitle = state.customHeaders.let { if (it.isEmpty()) "None — sent to the instance only" else "${it.size} set — sent to the instance only" },
                chevron = true,
                onClick = onOpenHeaders,
            )
        }
        Text(
            "Beacon ${BuildConfig.VERSION_NAME}" + (state.serverVersion?.let { " · server v$it" } ?: "") + " — the admin plane lives on the web.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
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

/** Mockup 09 grouped card. */
@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { content() }
    }
}

/** One row inside a SettingCard (icon · title/subtitle · value/chevron/toggle). */
@Composable
private fun SettingRow(
    icon: String,
    title: @Composable () -> Unit,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val clickable = onClick != null && enabled
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick!!) else Modifier)
            .padding(vertical = 12.dp),
    ) {
        Text(icon, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            title()
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        value?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        if (chevron) Text("›", color = MaterialTheme.colorScheme.outlineVariant)
    }
    HorizontalDivider()
}