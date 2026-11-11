package dev.beacon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.FolderRow
import dev.beacon.core.api.WorkspaceApis
import dev.beacon.core.data.ChatRepository
import dev.beacon.core.data.local.ChatEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Chats home (UI v2.0 §4.2, tab 1 / START destination): Room-backed chat rows
 * (Pinned / Folders / date groups come from the state-driven screen), folders
 * CRUD, search, row actions, pagination. The drawer's recents REPLACED —
 * one path to the data.
 */
@HiltViewModel
class ChatsHomeViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val apis: WorkspaceApis,
    private val owui: OwuiApi,
) : ViewModel() {

    data class UiState(
        val chats: List<ChatEntity> = emptyList(),
        val folders: List<FolderRow> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        /** Server-side search query (GET /api/v1/chats/search, chats.py:900). */
        val search: String = "",
    )

    private data class Load(val loading: Boolean = true, val error: String? = null)

    private val roomChats = repository.observeChats()
    private val load = MutableStateFlow(Load())
    private val foldersFlow = MutableStateFlow<List<FolderRow>>(emptyList())
    private val searchFlow = MutableStateFlow("")
    /** Server search hits for the active query; null = blank query or request failed (→ local filter). */
    private val serverSearchFlow = MutableStateFlow<List<ChatEntity>?>(null)
    private var page = 1
    private var exhausted = false

    init {
        refresh()
        // Debounced server search (chats.py:900); on failure fall back to the
        // local Room filter so the field never goes dead offline.
        viewModelScope.launch {
            searchFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { q ->
                    val query = q.trim()
                    serverSearchFlow.value = if (query.isBlank()) {
                        null
                    } else {
                        runCatching { repository.searchServer(query) }.getOrNull()
                    }
                }
        }
    }

    val ui: StateFlow<UiState> = combine(
        roomChats,
        foldersFlow,
        searchFlow,
        serverSearchFlow,
        load,
    ) { chats, folders, search, serverSearch, l ->
        val q = search.trim()
        UiState(
            chats = when {
                q.isBlank() -> chats
                serverSearch != null -> serverSearch
                else -> chats.filter { it.title.contains(q, ignoreCase = true) }
            },
            folders = if (q.isBlank()) folders else folders.filter { it.name.contains(q, ignoreCase = true) },
            loading = l.loading,
            error = l.error,
            search = search,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setSearch(q: String) {
        searchFlow.value = q
    }

    fun refresh() {
        viewModelScope.launch {
            load.value = Load(loading = true)
            // Independent fetches (defaults-chain rule): a folder failure must
            // not blank the chat list.
            val chats = runCatching { repository.refreshChats(page = 1) }
            page = 1
            exhausted = false
            foldersFlow.value = runCatching { apis.folders() }.getOrDefault(foldersFlow.value)
            load.value = Load(loading = false, error = chats.exceptionOrNull()?.message)
        }
    }

    /** Infinite scroll (§9.3): next chat page when the list end is reached. */
    fun loadNextPage() {
        if (exhausted || load.value.loading) return
        viewModelScope.launch {
            try {
                val before = repository.chatsCount()
                repository.refreshChats(page = page + 1)
                val after = repository.chatsCount()
                if (after == before) exhausted = true else page += 1
            } catch (_: Exception) {
                exhausted = true
            }
        }
    }

    fun togglePin(chatId: String) = viewModelScope.launch { repository.togglePin(chatId) }

    fun toggleArchive(chatId: String) = viewModelScope.launch { repository.toggleArchive(chatId) }

    fun delete(chatId: String) = viewModelScope.launch { repository.deleteChat(chatId) }

    /** POST /api/v1/chats/{id}/share → the {origin}/s/{share_id} link. */
    suspend fun shareLink(chatId: String): String? = runCatching { owui.shareChat(chatId) }.getOrNull()

    /** Folder writes (SurfaceWriteViewModel parity); lists re-read after. */
    fun createFolder(name: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { apis.createFolder(name) }
            foldersFlow.value = runCatching { apis.folders() }.getOrDefault(foldersFlow.value)
            onDone()
        }
    }

    fun renameFolder(folderId: String, name: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { apis.renameFolder(folderId, name) }
            foldersFlow.value = runCatching { apis.folders() }.getOrDefault(foldersFlow.value)
            onDone()
        }
    }

    fun deleteFolder(folderId: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { apis.deleteFolder(folderId) }
            foldersFlow.value = runCatching { apis.folders() }.getOrDefault(foldersFlow.value)
            onDone()
        }
    }
}
