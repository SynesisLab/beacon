package dev.beacon.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import okhttp3.OkHttpClient

/**
 * Renders the engine's part list inside a bubble (agent.md §4 contract).
 * Reasoning = collapsible card; ToolCall = chip w/ args/result; citations;
 * follow-ups; HtmlCard = the sandboxed WebView pane (M2-2).
 */
@Composable
fun PartsView(
    parts: List<dev.beacon.core.render.ChatPart>,
    modifier: Modifier = Modifier,
    onFollowUp: (String) -> Unit = {},
    onOpenFile: (fileId: String, name: String, mime: String, terminalServerId: String?, terminalPath: String?) -> Unit =
        { _, _, _, _, _ -> },
    httpClient: OkHttpClient? = null,
    instanceUrl: String? = null,
    iframeCsp: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is dev.beacon.core.render.ChatPart.Status -> StatusPill(part)
                is dev.beacon.core.render.ChatPart.Reasoning -> ReasoningCard(part)
                is dev.beacon.core.render.ChatPart.Markdown ->
                    MiniMarkdown(markdown = part.md, streaming = part.streaming)
                is dev.beacon.core.render.ChatPart.ToolCall -> ToolCallCard(part)
                is dev.beacon.core.render.ChatPart.Citations -> CitationsPill(part)
                is dev.beacon.core.render.ChatPart.FollowUps -> FollowUpRow(part, onFollowUp)
                is dev.beacon.core.render.ChatPart.HtmlCard ->
                    when {
                        httpClient != null && (part.html != null || part.srcUrl != null) ->
                            HtmlCardPane(part, httpClient, instanceUrl, iframeCsp)
                        else -> HtmlCardPlaceholder(part)
                    }
                is dev.beacon.core.render.ChatPart.File -> FileChip(part, onOpenFile)
                is dev.beacon.core.render.ChatPart.GeneratedImage -> Unit // M2: generation rendering next
            }
        }
    }
}

/** display_file inline chip (matches the web UI's file card affordance, not a JSON dump). */
@Composable
private fun FileChip(
    part: dev.beacon.core.render.ChatPart.File,
    onOpenFile: (fileId: String, name: String, mime: String, terminalServerId: String?, terminalPath: String?) -> Unit,
) {
    val isTerminal = part.fileId.isEmpty()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onOpenFile(
                    part.fileId,
                    part.name,
                    part.mime,
                    part.terminalServerId,
                    part.terminalPath,
                )
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = when (part.viewer) {
                    dev.beacon.core.render.FileViewer.Image -> "🖼️"
                    dev.beacon.core.render.FileViewer.Pdf -> "📄"
                    dev.beacon.core.render.FileViewer.Text -> "📝"
                    dev.beacon.core.render.FileViewer.AudioVideo -> "▶️"
                    dev.beacon.core.render.FileViewer.Download -> "📦"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Column(Modifier.weight(1f)) {
                Text(part.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    if (isTerminal) {
                        "Terminal file"
                    } else {
                        part.mime
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                "Open",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun StatusPill(part: dev.beacon.core.render.ChatPart.Status) {
    if (part.done) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp))
        Text(
            part.text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `<think>` styled collapsible (agent.md §7.4) with elapsed-time placeholder. */
@Composable
private fun ReasoningCard(part: dev.beacon.core.render.ChatPart.Reasoning) {
    var open by rememberSaveable { mutableStateOf(!part.collapsedByDefault) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
            ) {
                Text(
                    "Thinking",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (part.streaming) {
                    CircularProgressIndicator(Modifier.size(12.dp))
                    Box(Modifier.height(4.dp))
                }
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(open) {
                MiniMarkdown(
                    markdown = part.md,
                    streaming = part.streaming,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCallCard(part: dev.beacon.core.render.ChatPart.ToolCall) {
    var open by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
            ) {
                Text(
                    "🔧 ${part.name}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(part.status)
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(open) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    if (part.args.isNotBlank()) {
                        MiniMarkdown("```\n${part.args}\n```")
                    }
                    part.result?.let { result ->
                        MiniMarkdown("```\n${result.toString()}\n```")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: dev.beacon.core.render.ToolStatus) {
    Text(
        when (status) {
            dev.beacon.core.render.ToolStatus.Pending -> "queued"
            dev.beacon.core.render.ToolStatus.Running -> "running"
            dev.beacon.core.render.ToolStatus.Done -> "done"
            dev.beacon.core.render.ToolStatus.Failed -> "failed"
            dev.beacon.core.render.ToolStatus.Cancelled -> "stopped"
        },
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun CitationsPill(part: dev.beacon.core.render.ChatPart.Citations) {
    val count = part.reportedCount ?: part.items.size
    Text(
        "🔗 $count ${if (count == 1) "source" else "sources"}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun FollowUpRow(
    part: dev.beacon.core.render.ChatPart.FollowUps,
    onFollowUp: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        part.items.take(2).forEach { followUp ->
            androidx.compose.material3.Button(
                onClick = { onFollowUp(followUp) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            ) { Text(followUp, maxLines = 1) }
        }
    }
}

/** M2-2 fallback (only for callers without the shared client — legacy rows/tests). */
@Composable
internal fun HtmlCardPlaceholder(part: dev.beacon.core.render.ChatPart.HtmlCard) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(16.dp)) {
            Text(
                part.title ?: "Interactive card (WebView pane lands in M2-2)",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}