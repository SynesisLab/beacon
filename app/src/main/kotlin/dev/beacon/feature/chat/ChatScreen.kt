package dev.beacon.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.beacon.core.chat.ChatEngine
import kotlinx.coroutines.launch

/** Composer + bubble transcript (messaging-app style) + status/error surfaces. */
data class ChatActions(
    val onDraft: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onBack: () -> Unit,
    val onOpenModelPicker: () -> Unit,
    val onOpenFile: (fileId: String, name: String, mime: String, terminalServerId: String?, terminalPath: String?) -> Unit,
    val onFlickRefetch: () -> Unit = {},
    val onAppResume: () -> Unit = {},
    val onClearUpdatedPill: () -> Unit = {},
    /** M3-d: attach (docs/photos via the pickers) / remove a pending chip. */
    val onAttach: () -> Unit = {},
    val onRemoveAttachment: (ChatViewModel.Attachment) -> Unit = {},
    /** §9.3 composer toggles + temp chat. */
    val onToggleWebSearch: () -> Unit = {},
    val onToggleImageGeneration: () -> Unit = {},
    val onToggleCodeInterpreter: () -> Unit = {},
    val onToggleTemp: () -> Unit = {},
    /** §8 mic: tap-dictation/stop; the screen handles the permission prompt. */
    val onMicTap: () -> Unit = {},
    val onMicStop: () -> Unit = {},
    /** §8.4 read-aloud for an assistant bubble. */
    val onReadAloud: (messageId: String, text: String) -> Unit = { _, _ -> },
    val onRegenerate: () -> Unit = {},
)

// Interface reference (2026-11-02): the user = a soft warm-gray pill, LEFT-aligned;
// the assistant = plain serif text directly on the background (no bubble outline).
private val UserBubbleShape = RoundedCornerShape(12.dp)
private val BubblePadding = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatViewModel.UiState,
    actions: ChatActions,
    showModelPicker: Boolean,
    onDismissPicker: () -> Unit,
    onPickModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // §9.1 flick-to-refetch: at bottom + upward flick ≥ ~4/3 viewport/sec ⇒ silent sync.
    val flickConnection = remember(listState) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                val info = listState.layoutInfo
                val viewport = info.viewportSize.height.toFloat().coerceAtLeast(1f)
                val upward = -available.y
                val threshold = (viewport * (1f / 0.3f) * 0.4f) // 40% of viewport per 300ms
                val atBottom = listState.canScrollForward.not()
                if (atBottom && upward >= threshold) {
                    actions.onFlickRefetch()
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    LaunchedEffect(state.engine.messages.size, state.engine.streamingMessageId) {
        if (listState.canScrollForward.not()) {
            listState.scrollToItem(0)
        }
    }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        actions.onAppResume()
        onPauseOrDispose { }
    }

    // Updated pill auto-clears (subtle: fades via recomposition removal).
    LaunchedEffect(state.updatedPill) {
        if (state.updatedPill) {
            kotlinx.coroutines.delay(2_000)
            actions.onClearUpdatedPill()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.engine.chatId == null) "New chat" else "Chat") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Model chip: current model NAME (chat pick → user → server default),
                    // truncated to keep the bar calm.
                    Button(
                        onClick = actions.onOpenModelPicker,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            state.modelLabel ?: "Model",
                            maxLines = 1,
                        )
                    }
                },
            )
        },
        modifier = modifier.semantics { testTag = "chat" },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            state.engine.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.engine.statusText?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                )
            }

            // §9.1 pills: "Updated ✓" after a silent sync found changes; and the
            // "Latest ⇅" fallback whenever the list sits away from the bottom.
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                if (state.updatedPill) {
                    androidx.compose.material3.AssistChip(
                        onClick = actions.onClearUpdatedPill,
                        label = { Text("Updated ✓") },
                    )
                }
            }

            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(flickConnection),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                if (state.engine.messages.isEmpty()) {
                    // Empty-state hero, in the model's voice (user request 2026-11-02).
                    item(key = "empty_hero") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 24.dp),
                        ) {
                            Text(
                                text = "Hello, I'm ${state.modelLabel ?: "your model"}.",
                                style = MaterialTheme.typography.headlineLarge,
                            )
                            Text(
                                text = "Ask anything — everything runs on your server.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val messages = state.engine.messages.asReversed()
                items(messages.size, key = { messages[it].id }) { index ->
                    MessageBubble(
                        message = messages[index],
                        streamingMessageId = state.engine.streamingMessageId,
                        onFollowUp = actions.onDraft,
                        onOpenFile = actions.onOpenFile,
                        httpClient = state.httpClient,
                        instanceUrl = state.instanceUrl,
                    )
                }
            }

            // §9.1 fallback affordance when scrolled away from the newest content.
            val notAtBottom by androidx.compose.runtime.remember {
                androidx.compose.runtime.derivedStateOf { listState.canScrollForward }
            }
            if (notAtBottom) {
                val clickScope = androidx.compose.runtime.rememberCoroutineScope()
                androidx.compose.material3.TextButton(
                    onClick = {
                        clickScope.launch { listState.scrollToItem(0) }
                        actions.onFlickRefetch()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Latest ⇅") }
            }
            // §9.3 composer toggles (server-gated) + temp chat.
            val gate = state.featuresGate
            if (gate != null && (gate.enable_web_search == true || gate.enable_image_generation == true ||
                    gate.enable_code_interpreter == true)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    if (gate.enable_web_search == true) {
                        androidx.compose.material3.FilterChip(
                            selected = state.toggles.webSearch,
                            onClick = actions.onToggleWebSearch,
                            label = { Text("🌐 Web") },
                        )
                    }
                    if (gate.enable_image_generation == true) {
                        androidx.compose.material3.FilterChip(
                            selected = state.toggles.imageGeneration,
                            onClick = actions.onToggleImageGeneration,
                            label = { Text("🎨 Image") },
                        )
                    }
                    if (gate.enable_code_interpreter == true) {
                        androidx.compose.material3.FilterChip(
                            selected = state.toggles.codeInterpreter,
                            onClick = actions.onToggleCodeInterpreter,
                            label = { Text("⌨️ Code") },
                        )
                    }
                    androidx.compose.material3.FilterChip(
                        selected = state.tempMode,
                        onClick = actions.onToggleTemp,
                        label = { Text("Temp") },
                    )
                }
            }
            // M3-d: pending attachments (chips) + the attach affordance.
            if (state.attachments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    state.attachments.take(3).forEach { entry ->
                        androidx.compose.material3.InputChip(
                            selected = true,
                            onClick = { actions.onRemoveAttachment(entry) },
                            label = { Text(entry.name, maxLines = 1) },
                        )
                    }
                    if (state.attachments.size > 3) {
                        Text("+${state.attachments.size - 3}")
                    }
                }
            }
            state.attachmentError?.let { err ->
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
            // The composer bar (interface copy, 2026-11-02): one rounded container,
            // attach left, draft middle, mic + dark send/stop circle right.
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(40.dp),
                ) {
                    IconButton(onClick = actions.onAttach, enabled = !state.uploading) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Attach",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // §8 mic: tap = dictate (partials), tap-again = stop; server mode
                // (long-press) transcribes on release. Hidden when unavailable (§8.5).
                if (state.micAvailable) {
                    Surface(
                        shape = CircleShape,
                        color = when (state.mic) {
                            ChatViewModel.MicState.Idle -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(40.dp),
                    ) {
                        IconButton(
                            onClick = {
                                if (state.mic == ChatViewModel.MicState.Idle) {
                                    actions.onMicTap()
                                } else {
                                    actions.onMicStop()
                                }
                            },
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_mic),
                                contentDescription = if (state.mic == ChatViewModel.MicState.Idle) "Dictate" else "Stop dictation",
                                tint = when (state.mic) {
                                    ChatViewModel.MicState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onPrimary
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = actions.onDraft,
                    label = { Text("Message") },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTag = "composer" },
                )
                Surface(
                    shape = CircleShape,
                    color = if (state.engine.busy || state.draft.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(46.dp),
                ) {
                    IconButton(
                        onClick = if (state.engine.busy) actions.onStop else actions.onSend,
                        enabled = state.engine.busy || state.draft.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = if (state.engine.busy) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (state.engine.busy) "Stop" else "Send",
                            tint = if (state.engine.busy || state.draft.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }   // inner Row
        }   // composer container
        }   // Column
    }   // Scaffold content

    if (showModelPicker) {
        ModalBottomSheet(onDismissRequest = onDismissPicker) {
            LazyColumn(Modifier.padding(16.dp)) {
                items(state.models.size) { index ->
                    val model = state.models[index]
                    Button(
                        onClick = { onPickModel(model.id) },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth(),
                    ) { Text(model.name) }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatEngine.RenderMessage,
    streamingMessageId: String?,
    onFollowUp: (String) -> Unit,
    onOpenFile: (fileId: String, name: String, mime: String, terminalServerId: String?, terminalPath: String?) -> Unit,
    httpClient: okhttp3.OkHttpClient?,
    instanceUrl: String?,
    onRegenerate: () -> Unit = {},
    onReadAloud: (messageId: String, text: String) -> Unit = { _, _ -> },
) {
    val streaming = message.id == streamingMessageId
    if (message.role == "user") {
        // Left-aligned warm-gray pill (interface copy, 2026-11-02).
        Box(Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = UserBubbleShape,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .align(Alignment.CenterStart),
            ) {
                MiniMarkdown(
                    markdown = message.markdown,
                    modifier = BubblePadding,
                )
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            // Assistant text sits on the app background directly (Copernicus-voice,
            // no chrome) — parts render inside.
            Box(Modifier.fillMaxWidth()) {
                if (message.parts.isEmpty()) {
                    // Persisted legacy rows / tests without a part pipeline.
                    MiniMarkdown(
                        markdown = message.markdown,
                        streaming = streaming,
                        modifier = Modifier,
                    )
                } else {
                    PartsView(
                        parts = message.parts,
                        onFollowUp = onFollowUp,
                        onOpenFile = onOpenFile,
                        modifier = Modifier,
                        httpClient = httpClient,
                        instanceUrl = instanceUrl,
                    )
                }
            }

            // Assistant bubble footer: copy / regenerate / read-aloud (§9.3, §8.4).
            if (!streaming) {
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    TextButton(
                        onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.markdown)) },
                    ) { Text("Copy", style = MaterialTheme.typography.labelLarge) }
                    TextButton(onClick = onRegenerate) {
                        Text("Retry", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { onReadAloud(message.id, message.markdown) }) {
                        Text("🔊", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}