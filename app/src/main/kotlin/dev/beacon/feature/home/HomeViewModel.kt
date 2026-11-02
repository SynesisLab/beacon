package dev.beacon.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.OwuiApiException
import dev.beacon.core.api.dto.OwuiModel
import dev.beacon.core.data.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** M0 home: connected-server summary + model picker. Room-backed chat list lands in M1. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: OwuiApi,
    private val session: SessionStore,
) : ViewModel() {

    data class UiState(
        val serverUrl: String = "",
        val serverVersion: String? = null,
        val selectedModelId: String? = null,
        val signedInEmail: String? = null,
        val models: List<OwuiModel> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private data class LoadUi(
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val modelsState = MutableStateFlow<List<OwuiModel>>(emptyList())
    private val loadState = MutableStateFlow(LoadUi())

    val state: StateFlow<UiState> =
        combine(session.settings, modelsState, loadState) { settings, models, load ->
            UiState(
                serverUrl = settings.serverUrl,
                serverVersion = settings.serverVersion,
                selectedModelId = settings.selectedModelId,
                signedInEmail = settings.signedInEmail,
                models = models,
                loading = load.loading,
                error = load.error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loadState.value = LoadUi(loading = true)
            try {
                modelsState.value = api.models().items
                loadState.value = LoadUi(loading = false)
            } catch (e: Exception) {
                loadState.value = LoadUi(loading = false, error = describe(e))
            }
        }
    }

    fun choose(modelId: String) {
        viewModelScope.launch {
            session.update { it.copy(selectedModelId = modelId) }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            session.signOut()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is OwuiApiException -> if (e.code == 401 || e.code == 403) {
            "Signed out or rejected by the server (${e.code})."
        } else {
            "Models fetch failed (${e.code})"
        }
        is IOException -> "This instance is unreachable right now — retry when it's back."
        else -> e.message ?: e.javaClass.simpleName
    }
}