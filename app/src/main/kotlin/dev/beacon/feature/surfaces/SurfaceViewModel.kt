package dev.beacon.feature.surfaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.ChannelRow
import dev.beacon.core.api.FolderRow
import dev.beacon.core.api.FileRow
import dev.beacon.core.api.KnowledgeRow
import dev.beacon.core.api.MemoryRow
import dev.beacon.core.api.NoteRow
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.PromptRow
import dev.beacon.core.api.ToolRow
import dev.beacon.core.api.WorkspaceApis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One drawer-surface state (M4-d1): the lists + the capability gates + the
 * account summary. The surfaces' fetches run PARALLEL with a 15s cap each; a
 * slow/failed surface degrades its rows, never the whole drawer (user report:
 * a sequentially-loaded surface could pin the spinner forever).
 */
@HiltViewModel
class SurfaceViewModel @Inject constructor(
    private val loader: WorkspaceApisLoader,
) : ViewModel() {
    val state: StateFlow<LoaderState> = loader.state

    init {
        loader.refresh()
    }

    fun refresh() = loader.refresh()
}

data class LoaderState(
    val loading: Boolean = true,
    val error: String? = null,
    val folders: List<FolderRow> = emptyList(),
    val notes: List<NoteRow> = emptyList(),
    val memories: List<MemoryRow> = emptyList(),
    val channels: List<ChannelRow> = emptyList(),
    val files: List<FileRow> = emptyList(),
    val prompts: List<PromptRow> = emptyList(),
    val tools: List<ToolRow> = emptyList(),
    val knowledge: List<KnowledgeRow> = emptyList(),
    val accountName: String? = null,
    val accountEmail: String? = null,
    val foldersGate: Boolean = false,
    val notesGate: Boolean = false,
    val memoriesGate: Boolean = false,
    val channelsGate: Boolean = false,
    val workspaceGate: Boolean = false,
)

@Singleton
class WorkspaceApisLoader @Inject constructor(
    private val apis: WorkspaceApis,
    private val session: dev.beacon.core.data.SessionStore,
    private val api: OwuiApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LoaderState())
    val state: StateFlow<LoaderState> = _state

    init {
        scope.launch {
            // The account rows ride the session settings (onboarding-populated).
            launch {
                session.settings.collect { settings ->
                    _state.value = _state.value.copy(
                        accountName = settings.userName,
                        accountEmail = settings.signedInEmail,
                    )
                }
            }
            refresh()
        }
    }

    /** One capped, fault-isolated fetch; null rows on failure/timeouts. */
    private suspend fun <T> safeSlot(call: suspend () -> List<T>): List<T>? = try {
        withTimeout(15_000) { call() }
    } catch (_: Exception) {
        null
    }

    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            // Gates from /api/config (the verified keys;).
            configGateJob = scope.launch {
                try {
                    val features = api.config().features
                    _state.value = _state.value.copy(
                        foldersGate = features?.enable_folders == true,
                        notesGate = features?.enable_notes == true,
                        memoriesGate = features?.enable_memories == true,
                        channelsGate = features?.enable_channels == true,
                        workspaceGate = features?.enable_tools == true || features?.enable_functions == true,
                    )
                } catch (_: Exception) {
                }
            }

            val results = kotlinx.coroutines.coroutineScope {
                val f = async { safeSlot { apis.folders() } }
                val n = async { safeSlot { apis.notes() } }
                val me = async { safeSlot { apis.memories() } }
                val c = async { safeSlot { apis.channels() } }
                val fl = async { safeSlot { apis.files() } }
                val p = async { safeSlot { apis.prompts() } }
                val t = async { safeSlot { apis.tools() } }
                val k = async { safeSlot { apis.knowledge() } }
                val all = awaitAll(f, n, me, c, fl, p, t, k)
                @Suppress("UNCHECKED_CAST")
                all as List<List<Any>?>
            }
            val timedOut = results.count { it == null }
            _state.value = _state.value.copy(
                loading = false,
                folders = (results[0] as List<FolderRow>?) ?: _state.value.folders,
                notes = (results[1] as List<NoteRow>?) ?: _state.value.notes,
                memories = (results[2] as List<MemoryRow>?) ?: _state.value.memories,
                channels = (results[3] as List<ChannelRow>?) ?: _state.value.channels,
                files = (results[4] as List<FileRow>?) ?: _state.value.files,
                prompts = (results[5] as List<PromptRow>?) ?: _state.value.prompts,
                tools = (results[6] as List<ToolRow>?) ?: _state.value.tools,
                knowledge = (results[7] as List<KnowledgeRow>?) ?: _state.value.knowledge,
                error = if (timedOut > 0) {
                    "One or more surfaces timed out — pull down to reload."
                } else {
                    null
                },
            )
        }
    }

    private var configGateJob: kotlinx.coroutines.Job? = null
}