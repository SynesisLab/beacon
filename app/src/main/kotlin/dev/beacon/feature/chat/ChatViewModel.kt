package dev.beacon.feature.chat

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.dto.OwuiModel
import dev.beacon.core.chat.ChatEngine
import dev.beacon.core.chat.ChatEngineFactory
import dev.beacon.core.chat.FileAttachment
import dev.beacon.core.data.SessionStore
import dev.beacon.core.data.UploadQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-chat session screen: one [ChatEngine] + the model picker + attachments
 * (M3-d; the upload rides the WorkManager queue with retry/backoff, agent.md §10).
 * Model fallback chain (user request): pick → user setting → server's
 * `default_models` (/api/config) → the first model.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val factory: ChatEngineFactory,
    private val api: OwuiApi,
    private val session: SessionStore,
    @ApplicationContext private val appContext: Context,
    private val uploads: UploadQueue,
) : ViewModel() {

    data class Attachment(
        val name: String,
        val mime: String,
        val uri: android.net.Uri,
    )

    data class UiState(
        val engine: ChatEngine.ChatUiModel = ChatEngine.ChatUiModel(),
        val models: List<OwuiModel> = emptyList(),
        val selectedModelId: String? = null,
        val draft: String = "",
        /** HtmlCard panes share the app client (same-origin auth, §11). */
        val httpClient: okhttp3.OkHttpClient? = null,
        val instanceUrl: String? = null,
        /** §9.1: a background sync found changes — show the Updated pill. */
        val updatedPill: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
        val uploading: Boolean = false,
        val attachmentError: String? = null,
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
    private val attachments = MutableStateFlow<List<Attachment>>(emptyList())
    private val uploading = MutableStateFlow(false)
    private val attachmentError = MutableStateFlow<String?>(null)
    private var serverDefault: String? = null
    private val httpClient = api.publicClient()
    private val instanceUrl = session.instanceUrlSnapshot()

    val ui: StateFlow<UiState> =
        combine(
            engine.state,
            modelsState,
            selected,
            draft,
            justUpdated,
            attachments,
            uploading,
            attachmentError,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            UiState(
                engine = values[0] as ChatEngine.ChatUiModel,
                models = values[1] as List<OwuiModel>,
                selectedModelId = values[2] as String?,
                draft = values[3] as String,
                httpClient = httpClient,
                instanceUrl = instanceUrl,
                updatedPill = values[4] as Boolean,
                attachments = values[5] as List<Attachment>,
                uploading = values[6] as Boolean,
                attachmentError = values[7] as String?,
            )
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

    fun attach(name: String, mime: String, uri: android.net.Uri) {
        attachmentError.value = null
        attachments.value = attachments.value + Attachment(name, mime, uri)
    }

    fun removeAttachment(entry: Attachment) {
        attachments.value = attachments.value - entry
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty() || uploading.value) return
        val modelId = resolveModel() ?: return
        val pending = attachments.value.toList()
        if (pending.isEmpty()) {
            draft.value = ""
            engine.send(text, modelId)
            return
        }

        // M3-d: attachments upload first (queue: retry + backoff), ids ride the request.
        uploading.value = true
        attachmentError.value = null
        draft.value = ""
        viewModelScope.launch {
            val uploaded = ArrayList<FileAttachment>(pending.size)
            try {
                pending.forEach { entry ->
                    val resolvedMime = entry.mime.ifBlank {
                        appContext.contentResolver.getType(entry.uri) ?: "application/octet-stream"
                    }
                    val bytes = appContext.contentResolver.openInputStream(entry.uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        attachmentError.value = "Couldn't read ${entry.name} — try re-attaching."
                        return@launch
                    }
                    val id = uploads.upload(UploadQueue.Entry(entry.name, resolvedMime, ""), bytes)
                    if (id == null) {
                        attachmentError.value = "${entry.name} failed to upload (queued for retry) — resend to try again."
                        return@launch
                    }
                    uploaded.add(FileAttachment(id = id, name = entry.name))
                }
                attachments.value = emptyList()
                engine.send(text, modelId, uploaded)
            } catch (e: Exception) {
                attachmentError.value = e.message ?: "Upload failed"
            } finally {
                uploading.value = false
            }
        }
    }

    fun stop() = engine.stop()

    /** Lifecycle hook: silent reconciler sync on app resume (§9.1/§9.2). */
    fun onAppResume() {
        viewModelScope.launch {
            try {
                if (engine.sync()) justUpdated.value = true
            } catch (_: Exception) {
            }
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