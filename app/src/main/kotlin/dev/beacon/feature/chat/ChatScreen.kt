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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.beacon.core.chat.ChatEngine

/** Composer + bubble transcript (messaging-app style) + status/error surfaces. */
data class ChatActions(
    val onDraft: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onBack: () -> Unit,
    val onOpenModelPicker: () -> Unit,
    val onOpenFile: (fileId: String, name: String, mime: String) -> Unit,
)

// Gentle radii with padding ≥ corner clearance — Surface clips to shape, so
// larger radii than the text padding eat into the first/last lines (user report).
private val UserBubbleShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp,
)
private val AssistantBubbleShape = RoundedCornerShape(
    topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp,
)
private val BubblePadding = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)

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

    LaunchedEffect(state.engine.messages.size, state.engine.streamingMessageId) {
        if (listState.canScrollForward.not()) {
            listState.scrollToItem(0)
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

            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                val messages = state.engine.messages.asReversed()
                items(messages.size, key = { messages[it].id }) { index ->
                    MessageBubble(
                        message = messages[index],
                        streamingMessageId = state.engine.streamingMessageId,
                        onFollowUp = actions.onDraft,
                        onOpenFile = actions.onOpenFile,
                    )
                }
            }

            HorizontalDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
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
            }
        }
    }

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
    onOpenFile: (fileId: String, name: String, mime: String) -> Unit,
) {
    val streaming = message.id == streamingMessageId
    if (message.role == "user") {
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
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = AssistantBubbleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
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
                    )
                }
            }
        }
    }
}