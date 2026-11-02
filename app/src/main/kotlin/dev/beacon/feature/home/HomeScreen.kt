package dev.beacon.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.ui.theme.Paddings
import java.io.File
import java.time.Instant

/** Chat list (Room-backed §10). Actions hoisted for screenshot tests. */
data class ChatListActions(
    val onNewChat: () -> Unit,
    val onOpenChat: (ChatEntity) -> Unit,
    val onRefresh: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenModels: () -> Unit,
    val onTogglePin: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onLoadNextPage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeViewModel.UiState,
    actions: ChatListActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var menuFor by remember { mutableStateOf<ChatEntity?>(null) }

    // Infinite scroll trigger (§9.3)
    val nearEnd by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(nearEnd, state.chats.size) {
        if (state.chats.isNotEmpty() && nearEnd >= state.chats.size - 4) {
            actions.onLoadNextPage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Beacon") },
                actions = {
                    IconButton(onClick = actions.onOpenModels) {
                        Icon(Icons.Filled.List, contentDescription = "Models")
                    }
                    IconButton(onClick = actions.onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = actions.onNewChat) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        },
        modifier = modifier.semantics { testTag = "chat_list" },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.error != null) {
                Text(
                    state.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = Paddings.screenEdge.dp),
                )
            }
            if (state.chats.isEmpty() && !state.loading) {
                EmptyListHint()
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.chats.size, key = { state.chats[it].chatId }) { index ->
                    val chat = state.chats[index]
                    ListItem(
                        headlineContent = { Text(chat.title.ifBlank { "Untitled" }) },
                        supportingContent = { Text(formatWhen(chat.updatedAt)) },
                        trailingContent = {
                            TextButton(onClick = { if (menuFor?.chatId != chat.chatId) menuFor = chat else menuFor = null }) {
                                Text("•••")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { actions.onOpenChat(chat) },
                    )
                    HorizontalDivider()
                }
            }
            if (state.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                }
            }
        }
    }

    menuFor?.let { chat ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(16.dp)) {
                TextButton(
                    onClick = {
                        actions.onTogglePin(chat.chatId)
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (chat.pinned) "Unpin" else "Pin") }
                TextButton(
                    onClick = {
                        actions.onDelete(chat.chatId)
                        menuFor = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun EmptyListHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text("No chats yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap + to start a conversation. Chats sync from your Open WebUI server.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatWhen(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    val zoned = java.time.ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault())
    return zoned.toLocalDate().toString() + " " + zoned.toLocalTime().withSecond(0).withNano(0).toString().take(5)
}