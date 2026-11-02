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
    ) {
        /** Display label: the model's NAME when known, else its id. */
        val modelLabel: String?
            get() = models.firstOrNull { it.id == selectedModelId }?.name ?: selectedModelId
    }

    private val engine: ChatEngine = factory.create()
    private val modelsState = MutableStateFlow<List<OwuiModel>>(emptyList())
    private val selected = MutableStateFlow<String?>(session.settingsSnapshot().selectedModelId)
    private val draft = MutableStateFlow("")
    private var serverDefault: String? = null

    val ui: StateFlow<UiState> = combine(engine.state, modelsState, selected, draft) { e, m, sel, d ->
        UiState(e, m, sel, d)
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