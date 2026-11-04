package dev.beacon.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.List
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
    /** The chats drawer (boot surface = hamburger home; Claude-app structure). */
    val onOpenDrawer: () -> Unit = {},
    val onNewChat: () -> Unit = {},
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
    val onRate: (messageId: String, rating: Int) -> Unit = { _, _ -> },
    val onShare: (messageId: String, text: String) -> Unit = { _, _ -> },
    /** M4-d4+: the bottom tab bar's surface tabs. */
    val onOpenSurfaceTab: (String) -> Unit = {},
    /** M4-d3: the model's ask_user interaction (answered via the socket ack). */
    val onAnswerInput: (Map<String, String>) -> Unit = {},
    val onCancelInput: () -> Unit = {},
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
    /** Hold-to-set-default (user request): persists the model across sessions. */
    onSetDefault: (String) -> Unit = {},
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
                // One line, then ellipsis (user request): long titles must not
                // blow the bar open.
                title = {
                    Text(
                        state.engine.title ?: "New chat",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (state.engine.chatId == null) {
                        IconButton(onClick = actions.onOpenDrawer) {
                            // The drawer affordance on the boot surface (interface copy).
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chats")
                        }
                    } else {
                        IconButton(onClick = actions.onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // Temp chat: the ghost affordance top-right beside the
                    // model chip (Claude parity; the inline chip is gone).
                    IconButton(onClick = actions.onToggleTemp) {
                        Icon(
                            androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_ghost),
                            contentDescription = if (state.tempMode) "Exit temporary chat" else "Temporary chat",
                            tint = if (state.tempMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Model chip (restyled per the interface reference): the soft
                    // gray pill + chevron — not a filled coral button.
                    Surface(
                        onClick = actions.onOpenModelPicker,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(50),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                state.modelLabel ?: "Model",
                                maxLines = 1,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Claude's bottom tab bar on the chat surface (user request):
            // Chat / Folders / Notes / Files; workspace stays in the drawer.
            dev.beacon.feature.chat.BeaconTabBar(
                active = "chat",
                onSelect = { key ->
                    when (key) {
                        "chat" -> actions.onNewChat()
                        else -> actions.onOpenSurfaceTab(key)
                    }
                },
            )
        },
        modifier = modifier.semantics { testTag = "chat" },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding) // Scaffold already spent these insets — imePadding below must not double-count (the ghost gap over an open keyboard)
                .fillMaxSize()
                .imePadding()
                // The flick rides the WHOLE surface: a short, non-scrolling
                // transcript never dispatches a list fling ("flick to update
                // feels missing" — user report 2026-11-03).
                .nestedScroll(flickConnection),
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
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                if (state.engine.messages.isEmpty()) {
                    // Empty-state hero (Claude parity, pixel-measured 2026-11-03):
                    // a BIG pulsing starburst OVER a display-class serif line,
                    // centered; the sub-line drops to the page bottom.
                    item(key = "empty_hero") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                        ) {
                            if (state.tempMode) {
                                LandingCritter(size = 96.dp)
                                Text(
                                    text = "Temporary chat",
                                    style = MaterialTheme.typography.headlineLarge,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Text(
                                    text = "This chat stays out of your history and search, and nothing is saved to your server.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            } else {
                                LandingCritter(size = 96.dp)
                                Text(
                                    text = "Hello, I'm ${state.modelLabel ?: "your model"}.",
                                    style = MaterialTheme.typography.headlineLarge,
                                )
                                Text(
                                    text = "Ask anything — everything runs on your server.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                        onRegenerate = actions.onRegenerate,
                        onReadAloud = actions.onReadAloud,
                        onRate = actions.onRate,
                        onShare = actions.onShare,
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
            // §9.3 composer toggles (server-gated). Temp rides the topbar.
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
            // Muted honesty line above the composer (interface copy).
            Text(
                "AI can make mistakes. Your server does the thinking.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 2.dp),
            )
            // The composer bar (interface copy, 2026-11-02): one rounded container,
            // attach left, draft middle, mic + dark send/stop circle right.
            // Temp mode: a DASHED outline (the Incognito copy, 2026-11-02).
            val composerOutline = MaterialTheme.colorScheme.outline
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                border = if (!state.tempMode) {
                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (state.tempMode) {
                            Modifier.drawBehind {
                                drawRoundRect(
                                    color = composerOutline,
                                    cornerRadius = CornerRadius(24.dp.toPx()),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 1.dp.toPx(),
                                        pathEffect = androidx.compose.ui.graphics.PathEffect
                                            .dashPathEffect(floatArrayOf(14f, 12f)),
                                    ),
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
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
                // Claude-composer parity (measured 2026-11-03): NO outlined
                // field/frame — the text sits directly in the card with a
                // placeholder hint (h=144px vs my outlined 201px: the outline
                // + label chip ate ~120dp of width and inflated the height).
                BasicTextField(
                    value = state.draft,
                    onValueChange = actions.onDraft,
                    textStyle = dev.beacon.ui.theme.BeaconTypography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary,
                    ),
                    decorationBox = { inner ->
                        // Hint UNDER the text, one shared line box → the hint
                        // sits on the cursor row, centered like Claude's.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 6.dp)
                                .heightIn(min = 46.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (state.draft.isEmpty()) {
                                Text(
                                    "Message",
                                    style = dev.beacon.ui.theme.BeaconTypography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
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
        // "Select model" sheet (interface copy, 2026-11-02): name + description
        // rows, the chosen one checked; HOLD a model to set it as the default
        // (user request): the default = the persisted selection.
        ModalBottomSheet(onDismissRequest = onDismissPicker) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Select model",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(
                    // The jumping = the sheet re-measuring itself while the
                    // list scrolled past the drag limit (report 2026-11-03):
                    // give the list a stable max height.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp),
                ) {
                    items(state.models.size, key = { state.models[it].id }) { index ->
                        val model = state.models[index]
                        val chosen = model.id == state.selectedModelId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onPickModel(model.id) },
                                    onLongClick = { onSetDefault(model.id) },
                                ),
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                                    if (chosen) {
                                        Text(
                                            "  Default",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                model.description?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (chosen) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    // The model-sheet check is BLUE in the reference
                                    // (Claude's informational accent) → tertiary.
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
                Text(
                    "Hold a model to make it the default across Beacon.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
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
    onRate: (messageId: String, rating: Int) -> Unit = { _, _ -> },
    onShare: (messageId: String, text: String) -> Unit = { _, _ -> },
) {
    val streaming = message.id == streamingMessageId
    if (message.role == "user") {
        // Right-hugging warm pill (user request 2026-11-03: sent turns belong
        // on the right, Claude parity).
        Box(Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = UserBubbleShape,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .align(Alignment.CenterEnd),
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

            // Assistant content footer (Claude parity 2026-11-03): the same six
            // actions, muted-gray icons, generous pitch (~29dp measured).
            if (!streaming) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.markdown)) }) {
                        Icon(
                            androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_copy),
                            "Copy",
                            tint = iconTint,
                        )
                    }
                    IconButton(onClick = { onShare(message.id, message.markdown) }) {
                        Icon(
                            androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_share),
                            "Share",
                            tint = iconTint,
                        )
                    }
                    IconButton(onClick = { onReadAloud(message.id, message.markdown) }) {
                        Icon(
                            androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_speaker),
                            "Read aloud",
                            tint = iconTint,
                        )
                    }
                    IconButton(onClick = { onRate(message.id, 1) }) {
                        Icon(
                            androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_thumb_up),
                            "Good response",
                            tint = iconTint,
                        )
                    }
                    IconButton(onClick = { onRate(message.id, -1) }) {
                        Icon(
                            androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_thumb_down),
                            "Bad response",
                            tint = iconTint,
                        )
                    }
                    IconButton(onClick = onRegenerate) {
                        Icon(
                            androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_regenerate),
                            "Regenerate",
                            tint = iconTint,
                        )
                    }
                }
            }
        }
    }
}