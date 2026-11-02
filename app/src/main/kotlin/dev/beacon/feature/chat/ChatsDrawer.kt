package dev.beacon.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.feature.home.HomeViewModel

/**
 * The chats drawer (interface copy 2026-11-02: the Claude app keeps the home
 * surface on the composer and the chats behind a drawer). Recents + pinned,
 * long-lived actions via the row menu; Settings + About at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsDrawer(
    listViewModel: HomeViewModel,
    onOpenChat: (ChatEntity) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val state by listViewModel.ui.collectAsStateWithLifecycle()
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ChatEntity?>(null) }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            "Beacon",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        TextButton(
            onClick = { onNewChat(); onClose() },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text("+ New chat") }
        TextButton(
            onClick = { onOpenSettings(); onClose() },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text("Settings") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Recents",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            items(state.chats, key = { it.chatId }) { chat ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChat(chat); onClose() }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            chat.title.ifBlank { "Untitled" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            (if (chat.pinned) "📌 " else "") + formatWhen(chat.updatedAt),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { menuFor = chat }) { Text("•••") }
                }
            }
        }
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }

    menuFor?.let { chat ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(16.dp)) {
                TextButton(
                    onClick = { listViewModel.togglePin(chat.chatId); menuFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (chat.pinned) "Unpin" else "Pin") }
                TextButton(
                    onClick = { listViewModel.delete(chat.chatId); menuFor = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete") }
            }
        }
    }
}

private fun formatWhen(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    val zoned = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault())
    return zoned.toLocalDate().toString() + " " + zoned.toLocalTime().withSecond(0).withNano(0).toString().take(5)
}