package dev.beacon.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.data.SessionStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * More (v2 §4.7 / mockup 05, tab 4) — the drawer's retired rows live here:
 * everything that isn't a tab = ONE gated list. Gated rows VANISH (don't gray
 * out) when the /api/config flags are absent. Terminal lives in Files
 * (caption). Footer caption = app + server versions.
 */
@HiltViewModel
class MoreViewModel @Inject constructor(
    private val owui: OwuiApi,
    private val session: SessionStore,
) : ViewModel() {

    data class UiState(
        val workspaceGate: Boolean = true,
        val notesGate: Boolean = false,
        val memoriesGate: Boolean = false,
        val channelsGate: Boolean = false,
        val serverVersion: String? = null,
    )

    private val configFlow = MutableStateFlow(dev.beacon.core.api.dto.AppConfig())

    init {
        viewModelScope.launch {
            configFlow.value = runCatching { owui.config() }.getOrDefault(configFlow.value)
        }
    }

    fun reload() {
        viewModelScope.launch {
            configFlow.value = runCatching { owui.config() }.getOrDefault(configFlow.value)
        }
    }

    val ui: StateFlow<UiState> = combine(configFlow, session.settings) { config, settings ->
        UiState(
            workspaceGate = true,
            notesGate = config.features?.enable_notes == true,
            memoriesGate = config.features?.enable_memories == true,
            channelsGate = config.features?.enable_channels == true,
            serverVersion = config.version ?: settings.serverVersion,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
}

@Composable
fun MoreScreen(
    state: MoreViewModel.UiState,
    onOpenSurface: (String) -> Unit,
    onOpenSettings: () -> Unit,
    /** The bottom tab bar, provided by the host (active = More). */
    tabState: @Composable () -> Unit,
) {
    val appVersion = "1.0"

    Scaffold(
        topBar = { TabHeader("More") },
        bottomBar = tabState,
        modifier = Modifier.semantics { testTag = "more_home" },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            SectionGroup(
                modifier = Modifier.padding(12.dp),
            ) {
                if (state.workspaceGate) {
                    GroupRow(
                        title = "Workspace",
                        sub = "Prompts · Tools · Knowledge — read-only v1",
                        onClick = { onOpenSurface("workspace") },
                    )
                }
                if (state.memoriesGate) {
                    GroupRow(
                        title = "Memories",
                        sub = "what Beacon remembers",
                        onClick = { onOpenSurface("memories") },
                    )
                }
                if (state.channelsGate) {
                    GroupRow(
                        title = "Channels",
                        sub = "shared rooms · live",
                        onClick = { onOpenSurface("channels") },
                    )
                }
                GroupRow(
                    title = "Settings",
                    sub = null,
                    leading = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = onOpenSettings,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Terminal lives in Files (source chips) · Beacon $appVersion" +
                        (state.serverVersion?.let { " · server $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Serif TAB HEADER (mockup pagehead2): centered title + optional trailing slot. */
@Composable
fun TabHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 19.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.align(Alignment.Center),
        )
        if (trailing != null) {
            androidx.compose.foundation.layout.Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

@Composable
private fun SectionGroup(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp),
            )
            .fillMaxWidth(),
        content = content,
    )
}

/** One More row: icon/title + optional sub + value + chevron. */
@Composable
private fun GroupRow(
    title: String,
    sub: String?,
    leading: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f).padding(start = if (leading != null) 10.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
