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
import dev.beacon.core.data.modelNameFor
import dev.beacon.core.data.parseChipCsv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
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
        /** The admin's artifact-embed CSP (`ui.iframe_csp`), if the server sends one. */
        val iframeCsp: String? = null,
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
        /** Boot cache (ServerSettings, 2026-11-06): paints chip gates/flags and
         *  the resolved model NAME before the defaults chain lands. */
        val cachedSettings: dev.beacon.core.data.ServerSettings? = null,
    ) {
        /** Display label: name when known → boot-cache name → the id. */
        val modelLabel: String?
            get() = models.firstOrNull { it.id == selectedModelId }?.name
                ?: cachedSettings?.modelNameFor(selectedModelId)
                ?: selectedModelId
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
    /** ui.iframe_csp (admin CSP for artifact/embed cards) rides the /api/config fetch. */
    private val iframeCspFlow = MutableStateFlow<String?>(null)
    private var serverDefault: String? = null
    /** Boot cache seed (hot snapshot): the returning session paints instantly. */
    private val cachedSettings = MutableStateFlow<dev.beacon.core.data.ServerSettings?>(
        session.settingsSnapshot().takeIf { it.isOnboarded },
    )
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
            iframeCspFlow,
            cachedSettings,
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
                // Chip visibility at boot = the CACHED gates (the live config
                // replaces them when the fetch lands).
                featuresGate = (values[12] as dev.beacon.core.api.dto.Features?)
                    ?: cachedBootGate(values[14] as dev.beacon.core.data.ServerSettings?),
                mic = values[10] as MicState,
                micError = values[11] as String?,
                iframeCsp = values[13] as String?,
                cachedSettings = values[14] as dev.beacon.core.data.ServerSettings?,
                // §8.5: the mic STAYS visible; the tap requests the permission
                // (a hidden mic would never produce the prompt).
                micAvailable = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(engine = engine.state.value))

    init {
        val cache = session.settingsSnapshot()
        // Boot cache seed (2026-11-06): the chips paint with the LAST session's
        // server-determined defaults instead of blank-then-pop; the fresh chain
        // ORs over this at applyModelDefaults.
        val seedIds = dev.beacon.core.data.parseChipCsv(cache.cachedChipDefaults)
        if (cache.cachedChipDefaults != null) {
            toggles.value = Toggles(
                webSearch = "web" in seedIds,
                imageGeneration = "image" in seedIds,
                codeInterpreter = "code" in seedIds,
            )
        }
        viewModelScope.launch {
            // §9.3 share-target: pre-fill the draft when shared into Beacon.
            ShareSeed.pending?.let { seed ->
                ShareSeed.pending = null
                draft.value = seed
            }
            engine.load(savedStateHandle.get<String>(KEY_CHAT)?.takeUnless { it == NEW_CHAT })

            fetchDefaults()
            applyModelDefaults(selected.value)
            selected.value = selected.value ?: serverDefault
            applyModelDefaults(selected.value)
            persistDefaultsCache()
            // One retry pass when the first round landed empty (boot-race
            // networks); the app-resume re-entry covers later failures.
            if (modelsState.value.isEmpty() || configFeaturesFlow.value == null) {
                kotlinx.coroutines.delay(12_000)
                fetchDefaults()
                applyModelDefaults(selected.value)
                persistDefaultsCache()
            }
        }
        // (the voice partials collector starts per-dictation in startDictation —
        // a construction-time collect here would see the not-yet-initialized
        // `voice` property: the crash on 2026-11-02's device log.)
    }

    /** Write the boot cache: names json + chip defaults/gates csv. */
    private suspend fun persistDefaultsCache() {
        val names = kotlinx.serialization.json.buildJsonObject {
            modelsState.value.forEach { model ->
                put(model.id, kotlinx.serialization.json.JsonPrimitive(model.name ?: model.id))
            }
        }
        session.update { base ->
            base.copy(
                modelNamesJson = names.toString(),
                cachedChipDefaults = dev.beacon.core.data.chipsCsv(
                    buildList {
                        if (toggles.value.webSearch) add("web")
                        if (toggles.value.imageGeneration) add("image")
                        if (toggles.value.codeInterpreter) add("code")
                    },
                ),
                cachedChipGates = dev.beacon.core.data.chipsCsv(
                    buildList {
                        val gate = configFeaturesFlow.value
                        if (gate != null) {
                            if (gate.enable_web_search == true) add("web")
                            if (gate.enable_image_generation == true) add("image")
                            if (gate.enable_code_interpreter == true) add("code")
                        }
                    },
                ),
            )
        }
    }

    /** A partial Features from the cached gates (chip visibility at boot). */
    private fun cachedBootGate(settings: dev.beacon.core.data.ServerSettings?): dev.beacon.core.api.dto.Features? {
        val csv = dev.beacon.core.data.parseChipCsv(settings?.cachedChipGates)
        if (settings?.cachedChipGates == null) return null
        return dev.beacon.core.api.dto.Features(
            enable_web_search = "web" in csv,
            enable_image_generation = "image" in csv,
            enable_code_interpreter = "code" in csv,
        )
    }

    /**
     * The defaults chain, fetched INDEPENDENTLY and softly: any single failed
     * call no longer cancels the rest (device log 2026-11-03 — one throw
     * inside the old runCatching left every chip de-defaulted for the whole
     * session, with no retry).
     */
    private suspend fun fetchDefaults() {
        kotlinx.coroutines.coroutineScope {
            val models = async { runCatching { api.models().items }.getOrNull() }
            val config = async { runCatching { api.config() }.getOrNull() }
            val global = async { runCatching { api.globalDefaultFeatureIds() }.getOrNull() }
            val settings = async { runCatching { api.userSettings() }.getOrNull() }
            models.await()?.let { modelsState.value = it }
            config.await()?.also {
                configFeaturesFlow.value = it.features
                // OWUI admin-assigned default: "model-a,model-b" list → first id
                serverDefault = it.default_models
                    ?.split(',')?.firstOrNull()?.trim()
                    ?.takeIf { id -> id.isNotEmpty() }
                // §7.2: honor the admin's artifact-embed CSP when present.
                iframeCspFlow.value = it.ui?.iframe_csp?.takeIf { csp -> csp.isNotBlank() }
            }
            global.await()?.let { globalDefaultFeatureIds = it.orEmpty() }
            settings.await()?.let { userSettingsSnapshot = it }
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
            engine.send(text, modelId, features = currentFeatures(), temporary = tempMode.value, toolIds = toolSelection.value.toList(), skillIds = skillSelection.value.toList())
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
                engine.send(text, modelId, uploaded, toolIds = toolSelection.value.toList(), skillIds = skillSelection.value.toList())
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

    /** M4 approval: the pending non-ask_user tool call (rendered by ToolApprovalDialog). */
    val toolApproval: kotlinx.coroutines.flow.StateFlow<ChatEngine.ToolApprovalRequest?> =
        engine.toolApproval

    fun approveToolCall() = engine.approveToolCall()

    fun rejectToolCall() = engine.rejectToolCall()

    /** M4: the interceptor's dead-credential flag → the re-auth banner. */
    val authExpired: kotlinx.coroutines.flow.StateFlow<Boolean> = session.authExpired

    /** display_file auto-open: terminal:display_file resolved against the fold. */
    val fileDisplays: kotlinx.coroutines.flow.SharedFlow<dev.beacon.core.render.ChatPart.File> =
        engine.fileDisplays

    fun signOutNow() {
        viewModelScope.launch { session.signOut() }
    }

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

    /**
     * §9.1 flick-to-refetch gesture → silent sync. Live streams keep their
     * §9.1 immunity; a HUNG one (silent ≥ HANG_SILENCE_MS) is aborted and
     * re-synced — the user's escape hatch for a stuck generation (2026-11-06).
     */
    fun flickRefetch() {
        viewModelScope.launch {
            try {
                if (engine.flickSync()) justUpdated.value = true
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

    /** The admin's global defaultFeatureIds (configs/models/defaults). */
    private var globalDefaultFeatureIds: List<String> = emptyList()

    /** The raw users/user/settings object (diag + on/off reads). */
    private var userSettingsSnapshot: kotlinx.serialization.json.JsonObject? = null

    private fun alwaysOn(value: kotlinx.serialization.json.JsonElement?): Boolean =
        when (value) {
            is kotlinx.serialization.json.JsonPrimitive -> when {
                !value.isString ->
                    // boolean (content "true"/"false") or numeric 1/0
                    value.content.lowercase() == "true" || value.content == "1"
                else -> value.content.lowercase() in setOf("always", "enabled", "on", "true", "1")
            }
            else -> false
        }

    private fun alwaysOnFeature(settings: kotlinx.serialization.json.JsonObject?, feature: String): Boolean {
        val ui = settings?.get("ui") as? kotlinx.serialization.json.JsonObject ?: return false
        val legacyKey = when (feature) {
            "web_search" -> "webSearchEnabled"
            "image_generation" -> "imageGenerationEnabled"
            else -> feature
        }
        return when (feature) {
            "web_search" -> ui["webSearch"]
            "image_generation" -> ui["imageGeneration"]
            else -> null
        }?.let { alwaysOn(it) } == true ||
            alwaysOn(ui[legacyKey])
    }

    private fun applyModelDefaults(modelId: String?) {
        if (modelId == null || defaultsAppliedFor == modelId) return
        val model = modelsState.value.firstOrNull { it.id == modelId } ?: return
        val gate = configFeaturesFlow.value
        // The feature truth chain (web parity + Conduit's quick-pill semantics):
        // 1) the caller's ui.<feature> 'always'-mode (users/user/settings),
        // 2) the model's merged defaultFeatureIds — own first, then the admin
        //    global (utils/models.py:373-388 merge covers either server),
        // 3) the model's declared capabilities (Anthony's editor flags),
        // ALL gate-checked by /api/config's same-flag.
        val own = model.info?.meta?.defaultFeatureIds
        val ids = own ?: globalDefaultFeatureIds
        val caps = model.info?.meta?.capabilities ?: model.meta?.capabilities
        val gateOn = { feature: String ->
            gate?.let { f -> when (feature) {
                "web_search" -> f.enable_web_search
                "image_generation" -> f.enable_image_generation
                "code_interpreter" -> f.enable_code_interpreter
                else -> false
            } } == true
        }
        val capable = { feature: String -> when (feature) {
            "web_search" -> caps?.web_search
            "image_generation" -> caps?.image_generation
            "code_interpreter" -> caps?.code_interpreter
            else -> false
        } == true }
        val on = { feature: String ->
            (alwaysOnFeature(userSettingsSnapshot, feature) || ids.contains(feature) || capable(feature)) &&
                gateOn(feature)
        }
        android.util.Log.d(
            "BeaconDefaults",
            "model=$modelId own=$own global=$globalDefaultFeatureIds " +
                "caps=$caps ui=${userSettingsSnapshot?.get("ui")} " +
                "gate(webSearch=${gate?.enable_web_search},image=${gate?.enable_image_generation}," +
                "code=${gate?.enable_code_interpreter})",
        )
        toggles.value = Toggles(
            webSearch = on("web_search") || toggles.value.webSearch,
            imageGeneration = on("image_generation") || toggles.value.imageGeneration,
            codeInterpreter = on("code_interpreter") || toggles.value.codeInterpreter,
        )
        // The model's default TOOLS + SKILLS selections (Chat.svelte:1121-1140):
        // meta.toolIds (or the caller's settings.tools when the meta has none),
        // meta.skillIds — carried monotone-sticky across model changes until
        // the composer grows a selection menu (M4+).
        val metaTools = model.default_tool_ids
        val userPinned = pinnedTools(userSettingsSnapshot)
        toolSelection.value = (toolSelection.value + metaTools + userPinned)
        skillSelection.value = (skillSelection.value + model.default_skill_ids)
        android.util.Log.d(
            "BeaconDefaults",
            "model=$modelId tools=${toolSelection.value} skills=${skillSelection.value}",
        )
        defaultsAppliedFor = modelId
    }

    /** The caller's user-settings pinned tools ($settings.tools on the web). */
    private fun pinnedTools(settings: kotlinx.serialization.json.JsonObject?): List<String> {
        val raw = settings?.get("tools") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return raw.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    /** The workspace-tool selection riding the wire (tool_ids). */
    private val toolSelection = MutableStateFlow(emptySet<String>())
    private val skillSelection = MutableStateFlow(emptySet<String>())

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

    /** Regenerate with the resolved model (screen convenience); selections carry.
     *  An id = the PRESSED bubble → the version-tree branch at that location. */
    fun regenerate(atMessageId: String? = null) {
        val modelId = resolveModel() ?: return
        val tools = toolSelection.value.toList()
        val skills = skillSelection.value.toList()
        if (atMessageId == null) {
            engine.regenerate(modelId, toolIds = tools, skillIds = skills)
        } else {
            engine.regenerateAt(atMessageId, modelId, toolIds = tools, skillIds = skills)
        }
    }

    /** §4: the branch stepper's ◀/▶ (engine: leaf walk + server tree save). */
    fun setBranch(holderId: String, index: Int) = engine.setBranch(holderId, index)

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

    /**
     * Chat-level share (⋮ menu): POST /chats/{id}/share → `{origin}/s/{share_id}`
     * (chats.py:2083-2127; ShareChatModal.svelte:35). A server refusing the
     * chat.share permission reads as "not permitted" (401 at :2090).
     */
    fun shareChatLink() {
        val chatId = engine.state.value.chatId ?: return
        viewModelScope.launch {
            val shareId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { api.shareChat(chatId) }
            if (shareId == null) {
                toast("Sharing isn't permitted on this server.")
                return@launch
            }
            val url = "${session.instanceUrlSnapshot().orEmpty()}/s/$shareId"
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, url)
            }
            appContext.startActivity(
                android.content.Intent.createChooser(intent, "Share chat link")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Export the active branch as Markdown onto the clipboard. */
    fun copyChatAsMarkdown() {
        val state = engine.state.value
        val md = buildString {
            append("# ").append(state.title ?: "Chat").append("\n\n")
            state.messages.forEach { m ->
                if (m.markdown.isBlank()) return@forEach
                append("**").append(if (m.role == "user") "You" else m.role).append(":**\n\n")
                append(m.markdown.trim()).append("\n\n")
            }
        }
        copyText(md, "Markdown copied")
    }

    /** Export the active branch as a JSON transcript onto the clipboard. */
    fun copyChatAsJson() {
        val state = engine.state.value
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("title", state.title ?: "Chat")
            put(
                "messages",
                kotlinx.serialization.json.JsonArray(
                    state.messages.map { m ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("role", m.role)
                            put("content", m.markdown)
                        }
                    },
                ),
            )
        }
        copyText(payload.toString(), "JSON copied")
    }

    private fun copyText(text: String, toastMessage: String) {
        val clipboard = appContext.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Beacon chat", text))
        toast(toastMessage)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
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