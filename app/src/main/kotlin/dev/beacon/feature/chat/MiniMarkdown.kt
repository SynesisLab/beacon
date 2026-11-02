package dev.beacon.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * M1 transcript renderer (ADR-0009): segments streaming markdown into paragraphs
 * and fenced code blocks; fenced blocks render as horizontally-scrollable
 * monospace rows with copy/wrap. M2 replaces this with the full render pipeline
 * (segmenter → ChatPart + m3 markdown renderer, agent.md §7.1).
 *
 * [segmentMarkdown] is pure and table-tested; an open fence while streaming
 * renders as a live preview (agent.md §7.1 partial-HTML rule).
 */

enum class BlockKind { Paragraph, Code }

data class TextBlock(val kind: BlockKind, val text: String, val language: String? = null)

private val fenceLine = Regex("^\\s*(```+|~~~+)\\s*([a-zA-Z0-9_+-]*)\\s*$")

fun segmentMarkdown(md: String): List<TextBlock> {
    if (md.isBlank()) return emptyList()

    val blocks = ArrayList<TextBlock>()
    val paragraph = StringBuilder()
    var inFence = false
    var fenceLanguage: String? = null
    val fence = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim('\n')
        if (text.isNotBlank()) blocks.add(TextBlock(BlockKind.Paragraph, text))
        paragraph.setLength(0)
    }

    md.split('\n').forEach { line ->
        val opens = !inFence && fenceLine.matches(line)
        if (opens) {
            flushParagraph()
            inFence = true
            fenceLanguage = fenceLine.find(line)!!.groupValues[2].takeIf { it.isNotEmpty() }
            return@forEach
        }
        if (inFence) {
            if (fenceLine.matches(line)) {
                blocks.add(TextBlock(BlockKind.Code, fence.toString().trim('\n'), fenceLanguage))
                fence.setLength(0)
                inFence = false
                fenceLanguage = null
            } else {
                fence.append(line).append('\n')
            }
            return@forEach
        }
        if (line.isBlank()) {
            // Blank line ends a paragraph (markdown paragraph rule)
            flushParagraph()
            return@forEach
        }
        paragraph.append(line).append('\n')
    }

    if (inFence && fence.isNotBlank()) {
        blocks.add(TextBlock(BlockKind.Code, fence.toString().trim('\n'), fenceLanguage))
    }
    flushParagraph()
    return blocks
}

private val INLINE_MARKS = Regex("(\\*\\*[^*\\n]+\\*\\*)|(`[^`\\n]+`)|(\\*[^*\\n]+\\*)")

private fun inlineText(raw: String, caret: Boolean): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in INLINE_MARKS.findAll(raw)) {
        append(raw.substring(last, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.drop(2).dropLast(2)) }
            token.startsWith("`") ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(token.drop(1).dropLast(1)) }
            else ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.drop(1).dropLast(1)) }
        }
        last = match.range.last + 1
    }
    if (last < raw.length) append(raw.substring(last))
    if (caret) append(STREAMING_CARET)
}

private const val STREAMING_CARET = "▍"

@Composable
fun MiniMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val blocks = segmentMarkdown(markdown)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block.kind) {
                BlockKind.Paragraph -> Text(
                    text = inlineText(
                        block.text,
                        caret = streaming && block === blocks.last(),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                BlockKind.Code -> CodeBlock(text = block.text, language = block.language)
            }
        }
        if (blocks.isEmpty() && streaming) {
            Text(STREAMING_CARET, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CodeBlock(text: String, language: String?) {
    val clipboard = LocalClipboardManager.current
    var wrap by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = (language ?: "text").lowercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { wrap = !wrap }) { Text(if (wrap) "No wrap" else "Wrap") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                modifier = when (wrap) {
                    true -> Modifier
                    false -> Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                },
            )
        }
    }
}