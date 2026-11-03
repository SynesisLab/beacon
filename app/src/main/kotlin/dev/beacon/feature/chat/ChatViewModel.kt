package dev.beacon.feature.chat

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.json.Json
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
        /** §9.3 composer toggles + gates (hidden when the server doesn't expose them). */
        val toggles: Toggles = Toggles(),
        val tempMode: Boolean = false,
        val featuresGate: dev.beacon.core.api.dto.Features? = null,
        /** §8 voice state (the composer's mic + surfaced service errors). */
        val mic: MicState = MicState.Idle,
        val micError: String? = null,
        val micAvailable: Boolean = false,
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
    private val toggles = MutableStateFlow(Toggles())
    private val tempMode = MutableStateFlow(false)
    private val micState = MutableStateFlow<MicState>(MicState.Idle)
    private val micError = MutableStateFlow<String?>(null)
    private var dictationBase: String? = null
    /** The gate rides the combine as a REAL flow — a var read inside the lambda
     *  only paints when another input happens to re-emit afterwards
     *  (the vanishing toggle chips, 2026-11-03). */
    private val configFeaturesFlow = MutableStateFlow<dev.beacon.core.api.dto.Features?>(null)
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
            toggles,
            tempMode,
            micState,
            micError,
            configFeaturesFlow,
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
                toggles = values[8] as Toggles,
                tempMode = values[9] as Boolean,
                featuresGate = values[12] as dev.beacon.core.api.dto.Features?,
                mic = values[10] as MicState,
                micError = values[11] as String?,
                // §8.5: the mic STAYS visible; the tap requests the permission
                // (a hidden mic would never produce the prompt).
                micAvailable = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(engine = engine.state.value))

    init {
        viewModelScope.launch {
            // §9.3 share-target: pre-fill the draft when shared into Beacon.
            ShareSeed.pending?.let { seed ->
                ShareSeed.pending = null
                draft.value = seed
            }
            engine.load(savedStateHandle.get<String>(KEY_CHAT)?.takeUnless { it == NEW_CHAT })
            runCatching {
                modelsState.value = api.models().items
                val config = api.config()
                configFeaturesFlow.value = config.features
                // OWUI admin-assigned default: "model-a,model-b" list → first id
                serverDefault = config.default_models
                    ?.split(',')?.firstOrNull()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                applyModelDefaults(selected.value)
            }
            selected.value = selected.value ?: serverDefault
            applyModelDefaults(selected.value)
        }
        // (the voice partials collector starts per-dictation in startDictation —
        // a construction-time collect here would see the not-yet-initialized
        // `voice` property: the crash on 2026-11-02's device log.)
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

    /** §9.3 composer toggles, gated by the server's config capabilities. */
    data class Toggles(
        val webSearch: Boolean = false,
        val imageGeneration: Boolean = false,
        val codeInterpreter: Boolean = false,
    )

    fun toggleWebSearch() {
        toggles.value = toggles.value.copy(webSearch = !toggles.value.webSearch)
    }

    fun toggleImageGeneration() {
        toggles.value = toggles.value.copy(imageGeneration = !toggles.value.imageGeneration)
    }

    fun toggleCodeInterpreter() {
        toggles.value = toggles.value.copy(codeInterpreter = !toggles.value.codeInterpreter)
    }

    fun toggleTempMode() {
        tempMode.value = !tempMode.value
    }

    private fun currentFeatures(): dev.beacon.core.chat.Features = dev.beacon.core.chat.Features(
        web_search = toggles.value.webSearch && (configFeaturesFlow.value?.enable_web_search ?: true),
        image_generation = toggles.value.imageGeneration && (configFeaturesFlow.value?.enable_image_generation ?: true),
        code_interpreter = toggles.value.codeInterpreter && (configFeaturesFlow.value?.enable_code_interpreter ?: true),
    )

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty() || uploading.value) return
        val modelId = resolveModel() ?: return
        val pending = attachments.value.toList()
        if (pending.isEmpty()) {
            draft.value = ""
            engine.send(text, modelId, features = currentFeatures(), temporary = tempMode.value)
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

    /** M4-d3: the model's ask_user interaction (rendered by InputPromptDialog). */
    val inputPrompt: kotlinx.coroutines.flow.StateFlow<ChatEngine.InputRequest?> =
        engine.inputPrompt

    fun answerInput(answers: Map<String, String>) = engine.answerInput(answers)

    fun cancelInput() = engine.cancelInput()

    /**
     * +New chat while ALREADY on the chat/new surface (launchSingleTop makes
     * the navigate a no-op): reset the engine + composer so the surface is a
     * genuinely fresh chat.
     */
    fun resetForNewChat() {
        draft.value = ""
        attachments.value = emptyList()
        uploading.value = false
        attachmentError.value = null
        toggles.value = Toggles()
        tempMode.value = false
        viewModelScope.launch { engine.load(null) }
    }

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
        applyModelDefaults(modelId)
        viewModelScope.launch {
            session.update { it.copy(selectedModelId = modelId) }
        }
    }

    /**
     * OWUI per-model defaults (Chat.svelte:1114-1118; utils/automations.py:186):
     * a feature's chip starts ON when the SELECTED MODEL lists it in
     * defaultFeatureIds (and it's gate-enabled server-side). Re-applied per
     * model change, not per send (the user's manual taps stay sticky).
     */
    private var defaultsAppliedFor: String? = null

    private fun applyModelDefaults(modelId: String?) {
        if (modelId == null || defaultsAppliedFor == modelId) return
        val model = modelsState.value.firstOrNull { it.id == modelId } ?: return
        val gate = configFeaturesFlow.value
        val on = { feature: String ->
            model.default_feature_ids.contains(feature) &&
                gate?.let { f -> when (feature) {
                    "web_search" -> f.enable_web_search
                    "image_generation" -> f.enable_image_generation
                    "code_interpreter" -> f.enable_code_interpreter
                    else -> false
                } } == true
        }
        toggles.value = Toggles(
            webSearch = on("web_search") || toggles.value.webSearch,
            imageGeneration = on("image_generation") || toggles.value.imageGeneration,
        )
        defaultsAppliedFor = modelId
    }

    private fun resolveModel(): String? =
        selected.value
            ?: session.settingsSnapshot().selectedModelId
            ?: serverDefault
            ?: modelsState.value.firstOrNull()?.id

    // ── voice (agent.md §8; both engines) ───────────────────────────────────

    sealed interface MicState {
        data object Idle : MicState
        data object OnDevice : MicState
        data object ServerRecording : MicState
    }

    private val voice = dev.beacon.core.speech.VoiceInput(appContext)
    private val audioApi = dev.beacon.core.api.AudioApi(
        api.publicClient(),
        kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true },
        dev.beacon.core.api.InstanceSource { session.instanceUrlSnapshot() },
    )
    private val tts = dev.beacon.core.speech.TtsPlayer(audioApi)

    fun micAvailable(): Boolean = voice.hasPermission() &&
        android.speech.SpeechRecognizer.isRecognitionAvailable(appContext)

    fun micPermissionGranted(): Boolean = voice.hasPermission()

    /** Regenerate with the resolved model (screen convenience). */
    fun regenerate() {
        val modelId = resolveModel() ?: return
        engine.regenerate(modelId)
    }

    /** Tap = on-device dictation with live partials. The permission request stays on the screen. */
    private var partialsJob: kotlinx.coroutines.Job? = null

    fun startDictation() {
        if (!voice.hasPermission()) return
        micError.value = null
        dictationBase = draft.value.trimEnd()
        val started = voice.startOnDevice { error -> if (error != null) micError.value = error }
        if (!started) {
            micState.value = MicState.Idle
            return
        }
        micState.value = MicState.OnDevice
        // Live partials stream into the composer only while dictating (§8.1).
        partialsJob = viewModelScope.launch {
            voice.partials().collect { partial ->
                when (partial) {
                    is dev.beacon.core.speech.VoicePartial.Partial ->
                        draft.value = ((dictationBase ?: "") + " " + partial.text).trimStart()
                    is dev.beacon.core.speech.VoicePartial.Final -> {
                        draft.value = ((dictationBase ?: "") + " " + partial.text).trimStart()
                        dictationBase = null
                        micState.value = MicState.Idle
                    }
                    null -> Unit
                }
            }
        }
    }

    fun stopDictation() {
        partialsJob?.cancel()
        partialsJob = null
        voice.stopOnDevice()
        micState.value = MicState.Idle
        dictationBase = null
    }

    /** Long-press = server mode: record → release → transcribe → draft. */
    fun startServerDictation() {
        val error = voice.startServerRecording()
        micState.value = if (error == null) MicState.ServerRecording else MicState.Idle
        micError.value = error
    }

    fun finishServerDictation() {
        if (micState.value != MicState.ServerRecording) return
        micState.value = MicState.Idle
        val bytes = voice.stopServerRecording() ?: run {
            micError.value = "Recording was empty."
            return
        }
        viewModelScope.launch {
            try {
                val text = audioApi.transcribe("beacon-dictation.wav", "audio/wav", bytes, null)
                    ?: "Transcription failed — check the server's STT engine."
                draft.value = (draft.value + " " + text).trim()
            } catch (e: Exception) {
                micError.value = e.message ?: "Transcription failed"
            }
        }
    }

    /** Read-aloud (server TTS). */
    fun readAloud(messageId: String, text: String) {
        viewModelScope.launch {
            val ok = runCatching {
                tts.speak(appContext, messageId, text)
            }.getOrDefault(false)
            if (!ok) micError.value = "The server's TTS engine is unavailable."
        }
    }

    fun rate(messageId: String, rating: Int) {
        engine.rate(messageId, rating)
    }

    /** Share a response out via Android's chooser (the OWUI share-equivalent on mobile). */
    fun share(messageText: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, messageText)
        }
        appContext.startActivity(
            android.content.Intent.createChooser(intent, "Share response")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun stopTts() = tts.stop()

    override fun onCleared() {
        engine.close()
        voice.release()
        tts.release()
        super.onCleared()
    }

    companion object {
        const val KEY_CHAT = "chatId"
        const val NEW_CHAT = "new"
    }
}

/** §9.3: the single-slot bridge between the Activity's share-intents and the chat screen. */
object ShareSeed {
    var pending: String? = null
    val requestCount = MutableStateFlow(0)
}