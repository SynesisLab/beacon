package dev.beacon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.beacon.core.data.ChatRepository
import dev.beacon.core.data.local.ChatEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** M1 home: chats list (Room-backed, paginated §9.3), pin/delete, new chat. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ChatRepository,
) : ViewModel() {

    data class UiState(
        val chats: List<ChatEntity> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private data class Load(val loading: Boolean = false, val error: String? = null)

    private val roomChats = repository.observeChats()
    private val load = MutableStateFlow(Load(loading = true))
    private var page = 1
    private var lastPageCount = 0
    private var exhausted = false

    val ui: StateFlow<UiState> =
        combine(roomChats, load) { chats, l ->
            UiState(chats = chats, loading = l.loading, error = l.error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            load.value = Load(loading = true)
            try {
                page = 1
                repository.refreshChats(page = 1)
                lastPageCount = 0
                exhausted = false
                load.value = Load(loading = false)
            } catch (e: Exception) {
                load.value = Load(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Infinite scroll (§9.3): next page when the list end is reached. */
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

    fun togglePin(chatId: String) {
        viewModelScope.launch { repository.togglePin(chatId) }
    }

    fun delete(chatId: String) {
        viewModelScope.launch { repository.deleteChat(chatId) }
    }

    fun toggleArchive(chatId: String) {
        viewModelScope.launch { repository.toggleArchive(chatId) }
    }
}