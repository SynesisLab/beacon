package dev.beacon.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.beacon.core.chat.ChatEngine
import dev.beacon.ui.theme.Coral
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The pending tool-call approval (M4, tool_approval.py:119-136): Approve =
 * {action:'approve'} → the tools run; Reject = {action:'reject'} → the model
 * continues with a "rejected by user" result. Approval is BLOCKING (the
 * server holds the message until this resolves), so backdrop dismiss does
 * nothing — only Approve or Reject exit the dialog.
 */
@Composable
fun ToolApprovalDialog(
    request: ChatEngine.ToolApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    // Pretty-print the arguments JSON so a "which files?" glance is real.
    val pretty = remember(request.arguments) {
        if (request.arguments.isBlank()) "" else runCatching {
            val element: JsonElement = Json.parseToJsonElement(request.arguments)
            Json { prettyPrint = true }.encodeToString<JsonElement>(element)
        }.getOrNull() ?: request.arguments
    }
    AlertDialog(
        onDismissRequest = { },
        title = {
            Column {
                Text(
                    "Run tool?",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    request.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                )
            }
        },
        text = {
            if (pretty.isNotBlank()) {
                Text(
                    pretty.let { if (it.length > 1_200) it.take(1_200) + " …" else it },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            Button(onClick = onApprove) {
                Text("Approve", color = Coral)
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("Reject") }
        },
    )
}