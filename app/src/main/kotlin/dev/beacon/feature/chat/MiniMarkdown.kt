package dev.beacon.feature.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode

/**
 * Transcript markdown renderer (full GFM-level coverage, 2026-11-06):
 * blocks — paragraphs, ATX + setext headings, unordered/ordered NESTED lists,
 * task lists, fenced code, GFM pipe tables, blockquotes, horizontal rules;
 * inline — bold (** and __), italic (* and _), strikethrough, inline code,
 * links [t](u), autolinks <url>, bare URLs, images ![alt](url), raw HTML
 * tags stripped. [segmentMarkdown] + [inlineText] are pure and table-tested.
 */

enum class BlockKind { Paragraph, List_, Code, Heading, Table, Quote, Rule }

/** A list entry: [level] = nesting depth (0 = top), [checked] = -1 none / 0 todo / 1 done. */
data class ListItem(val text: String, val level: Int = 0, val checked: Int = -1)

data class TextBlock(
    val kind: BlockKind,
    val text: String,
    val language: String? = null,
    val ordered: Boolean = false,
    val items: List<ListItem> = emptyList(),
    val headingLevel: Int = 0,
    /** Table (GFM pipe tables): header cells + alignment + body rows. */
    val header: List<String> = emptyList(),
    val aligns: List<Int> = emptyList(), // 0 left, 1 center, 2 right
    val rows: List<List<String>> = emptyList(),
)

private val fenceLine = Regex("^\\s*(```+|~~~+)\\s*([a-zA-Z0-9_+-]*)\\s*$")
private val bulletLine = Regex("^(\\s{0,9})[-*+]\\s+(.*)$")
private val orderedLine = Regex("^(\\s{0,9})(\\d{1,3})[.)]\\s+(.*)$")
private val headingLine = Regex("^(#{1,6})\\s+(.*)$")
private val setextLine = Regex("^\\s{0,3}(=+|-+)\\s*$")
private val hrLine = Regex("^\\s{0,3}((?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")
private val quoteLine = Regex("^\\s{0,3}>\\s?(.*)$")
private val tableDelimiterLine = Regex("^\\s*\\|?[\\s:|-]*-[\\s:|-]*\\|?\\s*$")
private val taskPrefix = Regex("^\\[([ xX])\\]\\s+(.*)$")

private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split('|').map { it.trim() }
}

private fun alignOf(delim: String): Int = when {
    delim.startsWith(":") && delim.endsWith(":") -> 1
    delim.endsWith(":") -> 2
    else -> 0
}

fun segmentMarkdown(md: String): List<TextBlock> {
    if (md.isBlank()) return emptyList()

    val blocks = ArrayList<TextBlock>()
    val paragraph = StringBuilder()
    var inFence = false
    var fenceLanguage: String? = null
    val fence = StringBuilder()
    var listItems: ArrayList<ListItem>? = null
    var listOrdered = false
    val quote = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim('\n')
        if (text.isNotBlank()) blocks.add(TextBlock(BlockKind.Paragraph, text))
        paragraph.setLength(0)
    }

    fun flushList() {
        val items = listItems
        if (items != null && items.isNotEmpty()) {
            blocks.add(TextBlock(BlockKind.List_, "", ordered = listOrdered, items = items.toList()))
        }
        listItems = null
    }

    fun flushQuote() {
        val text = quote.toString().trim('\n')
        if (text.isNotBlank()) blocks.add(TextBlock(BlockKind.Quote, text))
        quote.setLength(0)
    }

    fun flushAll() {
        flushParagraph()
        flushList()
        flushQuote()
    }

    val lines = md.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        val fenceOpen = !inFence && fenceLine.matches(line)
        if (fenceOpen) {
            flushAll()
            inFence = true
            fenceLanguage = fenceLine.find(line)!!.groupValues[2].takeIf { it.isNotEmpty() }
            i++
            continue
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
            i++
            continue
        }

        // GFM pipe table: a |…| line followed by a delimiter row (which must
        // itself carry a pipe, else "a|b\n---" would be a setext heading).
        if (line.contains('|') && i + 1 < lines.size && lines[i + 1].contains('|') && tableDelimiterLine.matches(lines[i + 1])) {
            flushAll()
            val header = splitTableRow(line)
            val aligns = splitTableRow(lines[i + 1]).map { alignOf(it) }
                .let { it.ifEmpty { List(header.size) { 0 } } }
            val rows = ArrayList<List<String>>()
            var j = i + 2
            while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                val cells = splitTableRow(lines[j])
                rows.add(List(header.size) { k -> cells.getOrElse(k) { "" } })
                j++
            }
            blocks.add(
                TextBlock(
                    BlockKind.Table,
                    "",
                    header = header,
                    aligns = List(header.size) { k -> aligns.getOrElse(k) { 0 } },
                    rows = rows,
                ),
            )
            i = j
            continue
        }

        val heading = headingLine.find(line)
        if (heading != null) {
            flushAll()
            blocks.add(
                TextBlock(
                    BlockKind.Heading,
                    heading.groupValues[2],
                    headingLevel = heading.groupValues[1].length,
                ),
            )
            i++
            continue
        }

        // Setext heading: a paragraph line followed by === or ---.
        val setext = setextLine.find(line)
        if (setext != null && paragraph.isNotEmpty() && setext.groupValues[1].first() == '=') {
            val text = paragraph.toString().trim('\n')
            if (text.isNotBlank()) {
                blocks.add(TextBlock(BlockKind.Heading, text, headingLevel = 1))
            }
            paragraph.setLength(0)
            flushList()
            flushQuote()
            i++
            continue
        }

        if (hrLine.matches(line) && paragraph.isEmpty()) {
            flushAll()
            blocks.add(TextBlock(BlockKind.Rule, ""))
            i++
            continue
        }
        // A --- directly after a paragraph is a setext H2 (GFM precedence over HR).
        if (setext != null && paragraph.isNotEmpty() && setext.groupValues[1].first() == '-') {
            val text = paragraph.toString().trim('\n')
            if (text.isNotBlank()) {
                blocks.add(TextBlock(BlockKind.Heading, text, headingLevel = 2))
            }
            paragraph.setLength(0)
            flushList()
            flushQuote()
            i++
            continue
        }

        val quoteMatch = quoteLine.find(line)
        if (quoteMatch != null) {
            flushParagraph()
            flushList()
            if (quote.isNotEmpty()) quote.append('\n')
            quote.append(quoteMatch.groupValues[1])
            i++
            continue
        }
        flushQuote()

        val bullet = bulletLine.find(line)
        val ordered = orderedLine.find(line)
        if (bullet != null || ordered != null) {
            flushParagraph()
            flushQuote()
            val isOrdered = ordered != null
            if (listItems != null && listOrdered != isOrdered) flushList()
            if (listItems == null) listItems = ArrayList()
            listOrdered = isOrdered
            val raw = (bullet ?: ordered!!).groupValues[bullet?.let { 2 } ?: 3]
            val level = ((bullet ?: ordered!!).groupValues[1].length / 2).coerceIn(0, 4)
            val task = taskPrefix.find(raw)
            val itemText = task?.groupValues?.get(2) ?: raw
            val checked = task?.groupValues?.get(1)?.let { if (it == " " ) 0 else 1 } ?: -1
            listItems!!.add(ListItem(itemText, level, checked))
            i++
            continue
        }

        if (line.isBlank()) {
            flushAll()
            i++
            continue
        }

        flushList()
        flushQuote()
        paragraph.append(line).append('\n')
        i++
    }

    if (inFence && fence.isNotBlank()) {
        blocks.add(TextBlock(BlockKind.Code, fence.toString().trim('\n'), fenceLanguage))
    }
    flushAll()
    return blocks
}

private val IMAGE_TOKEN = Regex("!\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)")

private val INLINE_MARKS = Regex(
    "(!\\[[^\\]\\n]*\\]\\([^)\\s]+\\))" + // image (handled by the block layer; matched so links don't eat it)
        "|(\\*\\*[^*\\n]+\\*\\*)" +        // bold **
        "|(__[^_\\n]+__)" +                // bold __
        "|(`[^`\\n]+`)" +                 // inline code (ticks removed)
        "|(~~[^~\\n]+~~)" +                // strikethrough
        "|(\\*[^*\\n]+\\*)" +             // italic *
        "|(?<![\\w])_([^_\\n]+)_(?![\\w])" + // italic _ (not intraword)
        "|(\\[[^\\]\\n]+\\]\\([^)\\n]+\\))" + // markdown link [label](url)
        "|(<https?://[^>\\s]+>)" +          // autolink <url>
        "|(https?://[^\\s()<]+)" +          // bare URL
        "|(<[^>\\n]+>)"                      // raw HTML tag — stripped
)

/**
 * Inline marks → styled spans. Bold (double-star / double-underscore), italic
 * (single-star / single-underscore), strikethrough, inline code, tappable
 * links ([label](url), autolinks, bare URLs); raw HTML tags are stripped;
 * image tokens are consumed by the block layer (never linked). Links open
 * through an external chooser (browser); inline code always matches first,
 * so a URL inside backticks is never linked.
 */
internal fun inlineText(
    raw: String,
    caret: Boolean = false,
    linkColor: androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    val linkStyle = if (linkColor == androidx.compose.ui.graphics.Color.Unspecified) {
        TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))
    } else {
        TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
        )
    }
    var last = 0
    for (match in INLINE_MARKS.findAll(raw)) {
        append(raw.substring(last, match.range.first))
        val token = match.value
        when {
            token.startsWith("![") -> append(token) // image — the block layer renders it; inline fallback keeps the raw token
            token.startsWith("**") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.drop(2).dropLast(2)) }
            token.startsWith("__") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.drop(2).dropLast(2)) }
            token.startsWith("`") ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(token.drop(1).dropLast(1)) }
            token.startsWith("~~") ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.drop(2).dropLast(2)) }
            token.startsWith("*") ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.drop(1).dropLast(1)) }
            token.startsWith("_") ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[7]) }
            token.startsWith("[") -> {
                val label = token.removePrefix("[").substringBefore(']')
                val url = token.substringAfter("](").removeSuffix(")")
                if (label.isNotEmpty() && url.isNotEmpty()) {
                    pushLink(LinkAnnotation.Url(url, linkStyle) { LinkOpener.open(url) })
                    append(label)
                    pop()
                } else {
                    append(token)
                }
            }
            token.startsWith("<") && token.endsWith(">") && token.contains("http") -> {
                val url = token.removePrefix("<").removeSuffix(">")
                pushLink(LinkAnnotation.Url(url, linkStyle) { LinkOpener.open(url) })
                append(url)
                pop()
            }
            token.startsWith("<") && Regex("^<\\s*br\\s*/?\\s*>$", RegexOption.IGNORE_CASE).matches(token) -> append("\n") // <br> = line break
            token.startsWith("<") -> Unit // other raw HTML tags — stripped
            else -> {
                pushLink(LinkAnnotation.Url(token, linkStyle) { LinkOpener.open(token) })
                append(token)
                pop()
            }
        }
        last = match.range.last + 1
    }
    if (last < raw.length) append(raw.substring(last))
    if (caret) append(STREAMING_CARET)
}

/** Opens a link outside Beacon (a chooser; https/http only — §11 cross-origin). */
object LinkOpener {
    fun open(raw: String) {
        val context = contextHolder ?: return
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) return
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW, Uri.parse(raw)),
                    "Open link",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Set by the composition (the pure [inlineText] has no context). */
    @Volatile
    var contextHolder: android.content.Context? = null
        private set

    fun hold(context: android.content.Context) {
        contextHolder = context.applicationContext
    }
}

/** The orange starburst glyph: Claude's thinking marker (interface copy, 2026-11-02). */
private const val STREAMING_CARET = "✳"

/** Plain client for cross-origin images (never carries instance credentials). */
private val plainImageClient by lazy { okhttp3.OkHttpClient() }

@Composable
fun MiniMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    /** Instance-bound client (auth same-origin only); instance-relative image
     *  URLs resolve against [instanceUrl]. Null → external images only. */
    httpClient: okhttp3.OkHttpClient? = null,
    instanceUrl: String? = null,
) {
    // Links open externally (browser/chooser) — the opener needs a context.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember(context) { run { LinkOpener.hold(context); true } }
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = segmentMarkdown(markdown)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                BlockKind.Paragraph -> {
                    if (IMAGE_TOKEN.containsMatchIn(block.text)) {
                        ParagraphWithImages(
                            raw = block.text,
                            caret = streaming && block === blocks.last(),
                            linkColor = linkColor,
                            httpClient = httpClient,
                            instanceUrl = instanceUrl,
                        )
                    } else {
                        Text(
                            text = inlineText(block.text, caret = streaming && block === blocks.last(), linkColor = linkColor),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                BlockKind.List_ -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.padding(start = (item.level * 16).dp)) {
                            val marker = when {
                                item.checked == 1 -> "☑  "
                                item.checked == 0 -> "☐  "
                                block.ordered -> "${index + 1}.  "
                                else -> "•  "
                            }
                            Text(
                                text = marker,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = inlineText(
                                    item.text,
                                    caret = streaming && block === blocks.last() && index == block.items.lastIndex,
                                    linkColor = linkColor,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                BlockKind.Heading -> Text(
                    text = inlineText(block.text, linkColor = linkColor),
                    style = when {
                        block.headingLevel <= 1 -> MaterialTheme.typography.headlineSmall
                        block.headingLevel == 2 -> MaterialTheme.typography.titleLarge
                        block.headingLevel == 3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                BlockKind.Code -> CodeBlock(text = block.text, language = block.language)
                BlockKind.Table -> MarkdownTable(block)
                BlockKind.Quote -> QuoteBlock(block.text, streaming && block === blocks.last(), linkColor, httpClient, instanceUrl)
                BlockKind.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        if (blocks.isEmpty() && streaming) {
            ThinkingBurst()
        }
    }
}

/** A paragraph containing ![alt](url) images: text flows, images render inline (line granularity). */
@Composable
private fun ParagraphWithImages(
    raw: String,
    caret: Boolean,
    linkColor: androidx.compose.ui.graphics.Color,
    httpClient: okhttp3.OkHttpClient?,
    instanceUrl: String?,
) {
    // Pure segmentation first: a paragraph = alternating text runs / images.
    val segments = ArrayList<Any>() // String (text run) | MatchResult (image)
    val textBuffer = StringBuilder()
    fun flushText() {
        if (textBuffer.isNotBlank()) {
            segments.add(textBuffer.toString())
            textBuffer.setLength(0)
        }
    }
    raw.split('\n').forEach { line ->
        val images = IMAGE_TOKEN.findAll(line).toList()
        if (images.isEmpty()) {
            if (textBuffer.isNotEmpty()) textBuffer.append('\n')
            textBuffer.append(line)
            return@forEach
        }
        var last = 0
        for (m in images) {
            if (m.range.first > last) {
                if (textBuffer.isNotEmpty()) textBuffer.append('\n')
                textBuffer.append(line.substring(last, m.range.first))
            }
            flushText()
            segments.add(m)
            last = m.range.last + 1
        }
        if (last < line.length) {
            if (textBuffer.isNotEmpty()) textBuffer.append('\n')
            textBuffer.append(line.substring(last))
        }
    }
    flushText()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val lastIndex = segments.lastIndex
        segments.forEachIndexed { i, seg ->
            when (seg) {
                is String -> Text(
                    text = inlineText(seg, caret = caret && i == lastIndex, linkColor = linkColor),
                    style = MaterialTheme.typography.bodyLarge,
                )
                is MatchResult -> InlineImage(
                    url = seg.groupValues[2],
                    alt = seg.groupValues[1],
                    httpClient = httpClient,
                    instanceUrl = instanceUrl,
                )
            }
        }
    }
}

/** A remote/instance image fetched through OkHttp (no third-party image lib). */
@Composable
private fun InlineImage(url: String, alt: String, httpClient: okhttp3.OkHttpClient?, instanceUrl: String?) {
    val target = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") && !instanceUrl.isNullOrBlank() -> instanceUrl.trimEnd('/') + url
        else -> null
    }
    var image by remember(target) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (target == null) { failed = true; return@LaunchedEffect }
        // The shared instance client attaches credentials SAME-ORIGIN ONLY
        // (§11/ADR-0012); cross-origin images use the plain client.
        val client = if (httpClient != null && !instanceUrl.isNullOrBlank() && target.startsWith(instanceUrl.trimEnd('/'))) {
            httpClient
        } else {
            plainImageClient
        }
        val bytes = runCatching {
            client.newCall(okhttp3.Request.Builder().url(target).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
        val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (bmp != null) image = bmp.asImageBitmap() else failed = true
    }
    when {
        image != null -> androidx.compose.foundation.Image(
            bitmap = image!!,
            contentDescription = alt.ifBlank { null },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp),
        )
        failed -> Text(
            text = inlineText("![${alt}](${url})"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 160.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        )
    }
}

/** GFM pipe table: header row + body rows, horizontal scroll, per-column widths from content. */
@Composable
private fun MarkdownTable(block: TextBlock) {
    if (block.header.isEmpty()) return
    val colCount = block.header.size
    // Approximate per-column width from the longest cell (chars × ~7.5dp), clamped.
    val colDp = IntArray(colCount)
    fun measure(cells: List<String>) {
        for (c in 0 until colCount) {
            val len = cells.getOrElse(c) { "" }.length
            colDp[c] = maxOf(colDp[c], (len * 7.5f).toInt() + 24)
        }
    }
    measure(block.header)
    block.rows.forEach(::measure)
    val widths = colDp.map { it.dp.coerceIn(72.dp, 260.dp) }

    val alignText = { a: Int ->
        when (a) {
            1 -> TextAlign.Center
            2 -> TextAlign.Right
            else -> TextAlign.Left
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // header
        Row {
            block.header.forEachIndexed { c, cell ->
                Text(
                    text = cell,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = alignText(block.aligns.getOrElse(c) { 0 }),
                    modifier = Modifier.width(widths[c]),
                )
            }
        }
        HorizontalDivider()
        block.rows.forEach { row ->
            Row {
                row.forEachIndexed { c, cell ->
                    Text(
                        text = inlineText(cell),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = alignText(block.aligns.getOrElse(c) { 0 }),
                        modifier = Modifier.width(widths[c]),
                    )
                }
            }
        }
    }
}

/** Blockquote: leading bar + muted recursive markdown. */
@Composable
private fun QuoteBlock(
    text: String,
    caret: Boolean,
    linkColor: androidx.compose.ui.graphics.Color,
    httpClient: okhttp3.OkHttpClient?,
    instanceUrl: String?,
) {
    val bar = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val w = 3.dp.toPx()
                drawRect(bar, size = androidx.compose.ui.geometry.Size(w, size.height))
            }
            .padding(start = 12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniMarkdown(
                markdown = text,
                streaming = caret,
                httpClient = httpClient,
                instanceUrl = instanceUrl,
            )
        }
    }
}

/** The pulsing orange starburst (Claude's marker; scaleable — the hero runs big). */
@Composable
fun ThinkingBurst(size: Int = 28) {
    val transition = rememberInfiniteTransition(label = "burst")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(850),
            RepeatMode.Reverse,
        ),
        label = "burst-alpha",
    )
    Text(
        text = STREAMING_CARET,
        style = MaterialTheme.typography.displayLarge,
        fontSize = size.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
        fontWeight = FontWeight.Bold,
    )
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
