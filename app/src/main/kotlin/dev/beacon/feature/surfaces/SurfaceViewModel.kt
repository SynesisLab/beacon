package dev.beacon.feature.surfaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.ChannelRow
import dev.beacon.core.api.FolderRow
import dev.beacon.core.api.FileRow
import dev.beacon.core.api.KnowledgeRow
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.PromptRow
import dev.beacon.core.api.ToolRow
import dev.beacon.core.api.WorkspaceApis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads one drawer surface's list (M4-d1 read-only screens). Fetch failures stay
 * typed to the surface — the drawer hides what the server doesn't expose.
 */
@HiltViewModel
class SurfaceViewModel @Inject constructor(
    private val apis: WorkspaceApisLoader,
) : ViewModel() {

    data class ListState(
        val loading: Boolean = true,
        val error: String? = null,
        val folders: List<FolderRow> = emptyList(),
        val notes: List<dev.beacon.core.api.NoteRow> = emptyList(),
        val channels: List<ChannelRow> = emptyList(),
        val files: List<FileRow> = emptyList(),
        val prompts: List<PromptRow> = emptyList(),
        val tools: List<ToolRow> = emptyList(),
        val knowledge: List<KnowledgeRow> = emptyList(),
        // The drawer's account row + capability gates (server config owned).
        val accountName: String? = null,
        val accountEmail: String? = null,
        val foldersGate: Boolean = false,
        val notesGate: Boolean = false,
        val channelsGate: Boolean = false,
        val workspaceGate: Boolean = false,
    )

    val state: StateFlow<ListState> = apis.state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { apis.refresh() }
    }
}

/** Wrapper over WorkspaceApis exposing list state (kept thin for the drawer VMs). */
@Singleton
class WorkspaceApisLoader @Inject constructor(
    private val apis: WorkspaceApis,
    session: dev.beacon.core.data.SessionStore,
    private val api: OwuiApi,
) {
    private val _state = MutableStateFlow(SurfaceViewModel.ListState())
    val state: StateFlow<SurfaceViewModel.ListState> = _state

    init {
        // Gates + account ride the session settings (already loaded at onboarding).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            launch {
                session.settings.collect { settings ->
                    _state.value = _state.value.copy(
                        accountName = settings.userName,
                        accountEmail = settings.signedInEmail,
                    )
                }
            }
            refresh() // suspend-safe inside the scope's launch
        }
    }

    suspend fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        // Capability gates from /api/config (verified keys; §16.6).
        try {
            val cfg = api.config()
            val features = cfg.features
            _state.value = _state.value.copy(
                foldersGate = features?.enable_folders == true,
                notesGate = features?.enable_notes == true,
                channelsGate = features?.enable_channels == true,
                workspaceGate = features?.enable_tools == true || features?.enable_functions == true,
            )
        } catch (_: Exception) {
            // Unreachable server: rows degrade; gates read from the cached config on refresh.
        }
        suspend fun <T> fetch(slot: (SurfaceViewModel.ListState, List<T>) -> SurfaceViewModel.ListState, call: suspend () -> List<T>) {
            try {
                _state.value = slot(_state.value, call())
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e.javaClass.simpleName)
            }
        }
        fetch({ s, v -> s.copy(folders = v) }) { apis.folders() }
        fetch({ s, v -> s.copy(notes = v) }) { apis.notes() }
        fetch({ s, v -> s.copy(channels = v) }) { apis.channels() }
        fetch({ s, v -> s.copy(files = v) }) { apis.files() }
        fetch({ s, v -> s.copy(prompts = v) }) { apis.prompts() }
        fetch({ s, v -> s.copy(tools = v) }) { apis.tools() }
        fetch({ s, v -> s.copy(knowledge = v) }) { apis.knowledge() }
        _state.value = _state.value.copy(loading = false)
    }
}