package dev.beacon.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.dto.OwuiModel
import dev.beacon.core.chat.ChatEngine
import dev.beacon.core.chat.ChatEngineFactory
import dev.beacon.core.data.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-chat session screen: owns one [ChatEngine] + the model picker state.
 * Model fallback chain (user request): the chat's pick (persisted) → the user's
 * M0 selection → the server's `default_models` (/api/config) → the first model.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val factory: ChatEngineFactory,
    private val api: OwuiApi,
    private val session: SessionStore,
) : ViewModel() {

    data class UiState(
        val engine: ChatEngine.ChatUiModel = ChatEngine.ChatUiModel(),
        val models: List<OwuiModel> = emptyList(),
        val selectedModelId: String? = null,
        val draft: String = "",
        // HtmlCard panes share the app client (same-origin auth, §11).
        val httpClient: okhttp3.OkHttpClient? = null,
        val instanceUrl: String? = null,
        /** §9.1: a background sync found changes — show the Updated pill. */
        val updatedPill: Boolean = false,
    ) {
        /** Display label: the model's NAME when known, else its id. */
        val modelLabel: String?
            get() = models.firstOrNull { it.id == selectedModelId }?.name ?: selectedModelId
    }

    private val engine: ChatEngine = factory.create()
    private val modelsState = MutableStateFlow<List<OwuiModel>>(emptyList())
    private val selected = MutableStateFlow<String?>(session.settingsSnapshot().selectedModelId)
    private val draft = MutableStateFlow("")
    private val justUpdated = MutableStateFlow(false)
    private var serverDefault: String? = null
    private val httpClient = api.publicClient()
    private val instanceUrl = session.instanceUrlSnapshot()

    val ui: StateFlow<UiState> =
        combine(engine.state, modelsState, selected, draft, justUpdated) { e, m, sel, d, upd ->
            UiState(e, m, sel, d, httpClient, instanceUrl, upd)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(engine = engine.state.value))

    init {
        viewModelScope.launch {
            engine.load(savedStateHandle.get<String>(KEY_CHAT)?.takeUnless { it == NEW_CHAT })
            runCatching {
                modelsState.value = api.models().items
                // OWUI admin-assigned default: "model-a,model-b" list → first id
                serverDefault = api.config().default_models
                    ?.split(',')?.firstOrNull()?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            selected.value = selected.value ?: serverDefault
        }
    }

    fun setDraft(value: String) {
        draft.value = value
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        val modelId = resolveModel() ?: return
        draft.value = ""
        engine.send(text, modelId)
    }

    fun stop() = engine.stop()

    /** Lifecycle hook: silent reconciler sync on app resume (§9.1/§9.2). */
    fun onAppResume() {
        viewModelScope.launch {
            val changed = try {
                engine.sync()
            } catch (_: Exception) {
                false
            }
            if (changed) justUpdated.value = true
        }
    }

    /** §9.1: true right after a background sync found changes (Updated pill). */
    val updatedPill: StateFlow<Boolean> = justUpdated

    fun clearUpdatedPill() {
        justUpdated.value = false
    }

    /** §9.1 flick-to-refetch gesture → silent sync. */
    fun flickRefetch() {
        viewModelScope.launch {
            try {
                if (engine.sync()) justUpdated.value = true
            } catch (_: Exception) {
            }
        }
    }

    fun chooseModel(modelId: String) {
        selected.value = modelId
        viewModelScope.launch {
            session.update { it.copy(selectedModelId = modelId) }
        }
    }

    private fun resolveModel(): String? =
        selected.value
            ?: session.settingsSnapshot().selectedModelId
            ?: serverDefault
            ?: modelsState.value.firstOrNull()?.id

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }

    companion object {
        const val KEY_CHAT = "chatId"
        const val NEW_CHAT = "new"
    }
}