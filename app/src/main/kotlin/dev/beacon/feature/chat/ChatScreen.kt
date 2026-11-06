package dev.beacon.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.setValue
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
    /** Hero prompt chip (mockup 01): prefill + send. */
    val onSendPrompt: (String) -> Unit = {},
    /** Tool-card chip (input:prompt:submit): prefill the composer. */
    val onPrompt: (String) -> Unit = {},
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
    /** §4 version-tree: the pressed bubble's regen — id carries the location. */
    val onRegenerate: (String) -> Unit = {},
    /** The ◀/▶ branch stepper under a regenerated user turn. */
    val onSetBranch: (holderId: String, index: Int) -> Unit = { _, _ -> },
    val onRate: (messageId: String, rating: Int) -> Unit = { _, _ -> },
    val onShare: (messageId: String, text: String) -> Unit = { _, _ -> },
    /** Chat-level ⋮→ sheet actions. */
    val onShareChat: () -> Unit = {},
    val onCopyMarkdown: () -> Unit = {},
    val onCopyJson: () -> Unit = {},
    /** M4-d3: the model's ask_user interaction (answered via the socket ack). */
    val onAnswerInput: (Map<String, String>) -> Unit = {},
    val onCancelInput: () -> Unit = {},
)

// Interface reference (2026-11-02): the user = a soft warm-gray pill, LEFT-aligned;
// the assistant = plain serif text directly on the background (no bubble outline).
private val UserBubbleShape = RoundedCornerShape(12.dp)
private val BubblePadding = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)

/** Pull-up-to-refetch trigger distance (2026-11-06): a slow drag at the
 *  bottom of the transcript must travel this far before the sync fires. */
private const val PULL_TRIGGER_DP = 120

/** Test hook: pins the time-aware greeting's hour so goldens are stable. */
internal var testHourOverride: Int? = null

/** Test hook: pins the rotating greeting second line's day-of-year. */
internal var testDayOfYearOverride: Int? = null

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    state: ChatViewModel.UiState,
    actions: ChatActions,
    showModelPicker: Boolean,
    onDismissPicker: () -> Unit,
    onPickModel: (String) -> Unit,
    /** True while a flick re-sync is on the wire → the "Syncing…" pill. */
    syncing: Boolean = false,
    /** Hold-to-set-default (user request): persists the model across sessions. */
    onSetDefault: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // §9.1 flick-to-refetch (VISIBLE, user request 2026-11-03): the flick
    // bounces the surface up and pops a sync circle; the silent sync runs
    // underneath (+ the "Updated ✓" pill reports real changes).
    val flickScope = rememberCoroutineScope()
    var refetching by remember { mutableStateOf(false) }
    val pullUp = remember(listState) { androidx.compose.animation.core.Animatable(0f) }
    val fireFlick: () -> Unit = {
        if (listState.canScrollBackward.not() && !refetching) {
            flickScope.launch {
                refetching = true
                actions.onFlickRefetch()
                runCatching {
                    pullUp.snapTo(0f)
                    pullUp.animateTo(-30f, androidx.compose.animation.core.tween(150))
                    pullUp.animateTo(
                        0f,
                        androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                        ),
                    )
                }
                kotlinx.coroutines.delay(600)
                refetching = false
            }
        }
    }

    // §9.1 flick-to-refetch: at bottom + upward flick ≥ ~4/3 viewport/sec ⇒ sync.
    // 2026-11-06 (user ask): a slow PULL-UP from the bottom also refetches.
    // Detected with a raw pointer drag observer (not nestedScroll): a list
    // whose content is SHORTER than the viewport dispatches no scroll deltas
    // at all, and even on long chats the bottom edge reports through here.
    // The at-bottom check is canScrollBackward==false (reverseLayout: at the
    // visual bottom canScrollForward is TRUE — the 2026-11-06 inversion bug).
    val density = androidx.compose.ui.platform.LocalDensity.current
    var pullAccum by remember { mutableStateOf(0f) }
    // A hand-rolled observer that NEVER consumes (detectDragGestures consumes,
    // which killed the sidebar's left-edge swipe — user report 2026-11-06).
    // Axis-locked at the first slop crossing; only a VERTICAL pull that STARTS
    // at the bottom counts (a fast scroll merely ENDING at the bottom doesn't).
    val pullModifier = Modifier.pointerInput(listState) {
        val slop = with(density) { 8.dp.toPx() } // touch slop approximation in px
        var downX = 0f
        var lastY = 0f
        var axisLocked = false
        var horizontal = false
        var atBottomAtStart = false
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull() ?: continue
                if (c.type != androidx.compose.ui.input.pointer.PointerType.Touch) continue
                when (e.type) {
                    androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                        downX = c.position.x
                        lastY = c.position.y
                        axisLocked = false
                        horizontal = false
                        atBottomAtStart = listState.canScrollBackward.not()
                        pullAccum = 0f
                    }
                    androidx.compose.ui.input.pointer.PointerEventType.Move -> {
                        val y = c.position.y
                        val dy = y - lastY
                        if (!axisLocked) {
                            val dx = kotlin.math.abs(c.position.x - downX)
                            val ady = kotlin.math.abs(dy)
                            if (dx > slop || ady > slop) {
                                axisLocked = true
                                horizontal = dx > ady
                            }
                        }
                        if (axisLocked && !horizontal && atBottomAtStart && dy < 0f) {
                            pullAccum += -dy
                            if (pullAccum >= with(density) { PULL_TRIGGER_DP.dp.toPx() }) {
                                pullAccum = 0f
                                fireFlick()
                            }
                        }
                        lastY = y
                    }
                    androidx.compose.ui.input.pointer.PointerEventType.Release,
                    androidx.compose.ui.input.pointer.PointerEventType.Exit -> {
                        axisLocked = false
                        atBottomAtStart = false
                        pullAccum = 0f
                    }
                    else -> Unit
                }
                // never consume — the list and the drawer keep their gestures
            }
        }
    }
    val flickConnection = remember(listState) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                val info = listState.layoutInfo
                val viewport = info.viewportSize.height.toFloat().coerceAtLeast(1f)
                val upward = -available.y
                // §9.1 spec = 40% of viewport per 300ms — relaxed to 25%:
                // onPostFling sees only the UNCONSUMED remainder of the fling
                // (user report 2026-11-06: the flick often didn't register).
                val threshold = (viewport * (1f / 0.3f) * 0.25f)
                val atBottom = listState.canScrollBackward.not()
                if (atBottom && upward >= threshold) {
                    fireFlick()
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }

    LaunchedEffect(state.engine.messages.size, state.engine.streamingMessageId) {
        if (listState.canScrollBackward.not()) {
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
                // v3 §3.1 (amended, user report 2026-11-06): the chip wears the
                // model name + a ⌄ chevron, so TAP = the model sheet (the
                // 2026-11-06 long-press-only model sheet read as "the model
                // dropdown doesn't list the models"); LONG-PRESS = the chat
                // options menu (share/copy).
                title = {
                    var optionsOpen by remember { mutableStateOf(false) }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.combinedClickable(
                            onClick = actions.onOpenModelPicker,
                            onLongClick = { optionsOpen = true },
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .widthIn(max = 220.dp),
                        ) {
                            Text(
                                // New chat → the model; open chat → the title.
                                state.engine.title ?: (state.modelLabel ?: "Model"),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            androidx.compose.material3.Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = optionsOpen,
                            onDismissRequest = { optionsOpen = false },
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Share link") },
                                onClick = { optionsOpen = false; actions.onShareChat() },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Copy as Markdown") },
                                onClick = { optionsOpen = false; actions.onCopyMarkdown() },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Copy as JSON") },
                                onClick = { optionsOpen = false; actions.onCopyJson() },
                            )
                        }
                    }
                },
                navigationIcon = {
                    dev.beacon.feature.home.SidebarBubble(Modifier.padding(start = 2.dp))
                },
                actions = {
                    // Temp chat: the dashed ghost (coral when temporary).
                    IconButton(onClick = actions.onToggleTemp) {
                        Icon(
                            androidx.compose.ui.res.painterResource(dev.beacon.R.drawable.ic_ghost),
                            contentDescription = if (state.tempMode) "Exit temporary chat" else "Temporary chat",
                            tint = if (state.tempMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
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
                .consumeWindowInsets(padding) // Scaffold already spent these insets — imePadding below must not double-count (the ghost gap over an open keyboard)
                .fillMaxSize()
                .imePadding()
                // The flick rides the WHOLE surface: a short, non-scrolling
                // transcript never dispatches a list fling ("flick to update
                // feels missing" — user report 2026-11-03).
                .nestedScroll(flickConnection)
                .then(pullModifier),
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

            // The transcript + the sync-circle overlay (floats over the list's
            // bottom edge while the flick's sync runs).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = pullUp.value },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                ) {
                if (state.engine.loading && state.engine.messages.isEmpty()) {
                    // Opening an uncached OLD chat: the tree is still on the
                    // wire — a centered loading state, not the landing hero
                    // (user report 2026-11-06).
                    item(key = "chat_loading") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                            )
                            Text(
                                text = "Loading chat…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (state.engine.messages.isEmpty()) {
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
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "This chat stays out of your history and search, and nothing is saved to your server.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LandingCritter(size = 96.dp)
                                // Time-aware TWO-LINE greeting, centered (mockup 01:
                                // "Late night, Anthony?<br>Ready when you are").
                                // fillMaxWidth + textAlign=Center — a bare Text in
                                // the column rendered left-shifted on device
                                // (user report 2026-11-06).
                                val hour = testHourOverride
                                    ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                                val part = when (hour) {
                                    in 5..11 -> "morning"
                                    in 12..17 -> "afternoon"
                                    in 18..22 -> "evening"
                                    else -> "night"
                                }
                                // First name only for the greeting (user ask
                                // 2026-11-06 — the sidebar keeps the full name).
                                val name = state.cachedSettings?.userName
                                    ?.takeIf { it.isNotBlank() }
                                    ?.substringBefore(' ')
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "there"
                                // Rotating second line (user ask 2026-11-06 —
                                // not always "Ready when you are"); rotates by
                                // day-of-year so it changes daily but is stable
                                // within a day.
                                val secondLines = listOf(
                                    "Ready when you are",
                                    "What are we working on?",
                                    "Ask me anything",
                                    "Here when you need me",
                                    "Let's figure something out",
                                )
                                val dayOfYear = testDayOfYearOverride
                                    ?: java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
                                val second = secondLines[dayOfYear % secondLines.size]
                                Text(
                                    text = "Good $part, $name?\n$second",
                                    style = MaterialTheme.typography.headlineLarge,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "Ask ${state.modelLabel ?: "your model"} anything — everything runs on your server.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                // Mockup 01 .pchips: the server's
                                // default_prompt_suggestions as ≤3 coral-tint
                                // ✦ pills, centered + wrapping, directly below
                                // the greeting; tap sends.
                                if (!state.tempMode && state.promptSuggestions.isNotEmpty()) {
                                    androidx.compose.foundation.layout.FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 4.dp),
                                    ) {
                                        state.promptSuggestions.take(3).forEach { s ->
                                            Surface(
                                                onClick = { actions.onSendPrompt(s.contentText) },
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                                                color = dev.beacon.ui.theme.Coral.copy(alpha = 0.12f),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    dev.beacon.ui.theme.Coral.copy(alpha = 0.35f),
                                                ),
                                            ) {
                                                Text(
                                                    text = buildAnnotatedString {
                                                        withStyle(SpanStyle(color = dev.beacon.ui.theme.CoralDeep)) {
                                                            append("✦ ")
                                                        }
                                                        append(s.titleText)
                                                    },
                                                    maxLines = 1,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                                                )
                                            }
                                        }
                                    }
                                }
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
                        iframeCsp = state.iframeCsp,
                        onRegenerate = { actions.onRegenerate(messages[index].id) },
                        onReadAloud = actions.onReadAloud,
                        onRate = actions.onRate,
                        onShare = actions.onShare,
                        onPrompt = actions.onPrompt,
                        onSetBranch = actions.onSetBranch,
                    )
                }
                }
                // A brand-new chat (no id, no messages) has nothing to
                // refetch until the first message is sent (user report
                // 2026-11-06) — no pill during the pull either.
                val canSync = state.engine.chatId != null || state.engine.messages.isNotEmpty()
                if (syncing || refetching || (pullAccum > 0f && canSync)) {
                    // Visible during the PULL itself (user report 2026-11-06:
                    // the loading pill only appeared once the network call ran —
                    // too subtle), while the bounce plays, and while syncing.
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(bottom = 14.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                            Text("Syncing…", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // The "Latest ⇅" fallback pill was REMOVED (user request
            // 2026-11-06): the flick-up gesture + the Updated ✓ pill cover
            // the sync affordance; a permanent bottom button cluttered the
            // composer row.
            // Context meter (context_usage from GET /{id}; verified shape in
            // the clone): tokenized estimate vs the compaction threshold.
            val context = state.engine.contextUsage
            if (context != null && (context.percent ?: 0) > 0) {
                val pct = context.percent ?: 0
                Text(
                    "Context $pct%" + (context.tokens?.let { " · ${it / 1000}k tokens" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (pct >= 70) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            // §9.3 composer toggles REMOVED from above the composer (user
            // request 2026-11-06; mockup 01 fnote: "single-row composer, ⊕
            // sheet holds the toggles") — Web/Image/Code live in the
            // add-to-chat sheet only.
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
        // Mockup 04: handle + "Model" + ✕, search, avatar-initial rows with
        // caps badges, coral ✓ on the chosen one; hold = set app default.
        var query by remember { mutableStateOf("") }
        val visible = state.models.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
        }
        ModalBottomSheet(onDismissRequest = onDismissPicker) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 4.dp)
                        .width(32.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Text("Model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismissPicker) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    shape = CircleShape,
                    placeholder = { Text("Search models") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                ) {
                    items(visible.size, key = { visible[it].id }) { index ->
                        val model = visible[index]
                        val chosen = model.id == state.selectedModelId
                        val gate = state.featuresGate
                        val badges = buildList {
                            if ("web_search" in model.default_feature_ids && gate?.enable_web_search == true) add("🌐 web")
                            if ("image_generation" in model.default_feature_ids && gate?.enable_image_generation == true) add("🖼 images")
                            if ("code_interpreter" in model.default_feature_ids && gate?.enable_code_interpreter == true) add("⌘ code")
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onPickModel(model.id) },
                                    onLongClick = { onSetDefault(model.id) },
                                )
                                .padding(vertical = 11.dp),
                        ) {
                            // Avatar initials (mockup 04: blue-tinted tile).
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = androidx.compose.ui.graphics.Color(0xFFEFF3FE),
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        model.name.split(Regex("[\\s_\\-]+")).filter { it.isNotBlank() }
                                            .take(2).joinToString("") { it.first().uppercase() },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color(0xFF3565F2),
                                    )
                                }
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
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
                                val sub = buildString {
                                    model.description?.let { append(it) }
                                    if (badges.isNotEmpty()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(badges.joinToString(" "))
                                    }
                                }
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (chosen) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Text(
                    "Per-chat default · GET /api/v1/models/list?page=1 · hold a model to make it the app default.",
                    style = MaterialTheme.typography.labelSmall,
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
    iframeCsp: String? = null,
    onRegenerate: () -> Unit = {},
    onReadAloud: (messageId: String, text: String) -> Unit = { _, _ -> },
    onRate: (messageId: String, rating: Int) -> Unit = { _, _ -> },
    onShare: (messageId: String, text: String) -> Unit = { _, _ -> },
    onPrompt: (String) -> Unit = {},
    /** Branch stepper / the version-tree fork (§4). */
    onSetBranch: (holderId: String, index: Int) -> Unit = { _, _ -> },
) {
    val streaming = message.id == streamingMessageId
    // The native compaction checkpoint renders as its own row ABOVE the
    // message content (web ContextCompactionLabel parity) — split it out so
    // a part-bearing USER turn never loses its markdown to PartsView.
    val compactions = message.parts.filterIsInstance<dev.beacon.core.render.ChatPart.Compaction>()
    val restParts = message.parts.filterNot { it is dev.beacon.core.render.ChatPart.Compaction }
    if (compactions.isNotEmpty()) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
            compactions.forEach { part ->
                dev.beacon.feature.chat.CompactionRow(part)
            }
            MessageBody(
                message = message,
                streaming = streaming,
                parts = restParts,
                onFollowUp = onFollowUp,
                onOpenFile = onOpenFile,
                httpClient = httpClient,
                instanceUrl = instanceUrl,
                iframeCsp = iframeCsp,
                onRegenerate = onRegenerate,
                onReadAloud = onReadAloud,
                onRate = onRate,
                onShare = onShare,
                onPrompt = onPrompt,
                onSetBranch = onSetBranch,
            )
        }
    } else {
        MessageBody(
            message = message,
            streaming = streaming,
            parts = restParts,
            onFollowUp = onFollowUp,
            onOpenFile = onOpenFile,
            httpClient = httpClient,
            instanceUrl = instanceUrl,
            iframeCsp = iframeCsp,
            onRegenerate = onRegenerate,
            onReadAloud = onReadAloud,
            onRate = onRate,
            onShare = onShare,
            onPrompt = onPrompt,
            onSetBranch = onSetBranch,
        )
    }
}

/** The bubble body: user pill or assistant on-canvas parts/markdown. */
@Composable
private fun MessageBody(
    message: ChatEngine.RenderMessage,
    streaming: Boolean,
    parts: List<dev.beacon.core.render.ChatPart>,
    onFollowUp: (String) -> Unit,
    onOpenFile: (fileId: String, name: String, mime: String, terminalServerId: String?, terminalPath: String?) -> Unit,
    httpClient: okhttp3.OkHttpClient?,
    instanceUrl: String?,
    iframeCsp: String?,
    onRegenerate: () -> Unit,
    onReadAloud: (messageId: String, text: String) -> Unit,
    onRate: (messageId: String, rating: Int) -> Unit,
    onShare: (messageId: String, text: String) -> Unit,
    onPrompt: (String) -> Unit = {},
    onSetBranch: (holderId: String, index: Int) -> Unit,
) {
    if (message.role == "user") {
        // Right-hugging warm pill (user request 2026-11-03: sent turns belong
        // on the right, Claude parity). Long-press = select/copy (v1).
        Box(Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = UserBubbleShape,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .align(Alignment.CenterEnd),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer(Modifier) {
                    MiniMarkdown(
                        markdown = message.markdown,
                        modifier = BubblePadding,
                    )
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            // Assistant text sits on the app background directly (Copernicus-voice,
            // no chrome) — parts render inside.
            Box(Modifier.fillMaxWidth()) {
                if (parts.isEmpty()) {
                    // Persisted legacy rows / tests without a part pipeline,
                    // and long-press select/copy (user request 2026-11-03).
                    androidx.compose.foundation.text.selection.SelectionContainer(Modifier) {
                        MiniMarkdown(
                            markdown = message.markdown,
                            streaming = streaming,
                            modifier = Modifier,
                        )
                    }
                } else {
                    PartsView(
                        parts = parts,
                        onFollowUp = onFollowUp,
                        onOpenFile = onOpenFile,
                        modifier = Modifier,
                        httpClient = httpClient,
                        instanceUrl = instanceUrl,
                        iframeCsp = iframeCsp,
                        onPrompt = onPrompt,
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
                    // §4 branch stepper BESIDE the regen button (user request
                    // 2026-11-03): ‹ 2/3 › among this response's versions.
                    if (message.branchCount > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onSetBranch(message.id, (message.branchIndex - 1 + message.branchCount) % message.branchCount) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Text("‹", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "${message.branchIndex + 1}/${message.branchCount}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            IconButton(
                                onClick = { onSetBranch(message.id, (message.branchIndex + 1) % message.branchCount) },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Text("›", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}