package dev.beacon.core.render

import java.net.URLDecoder

/**
 * The markdown → [ChatPart] segmenter (agent.md §7.1; concept-port of Conduit's
 * markdown pipeline, original code). Runs on the CONTENT accumulation (text-mode
 * messages).
 *
 * Handled segment types, evidence-verified (docs/protocol-notes.md M2):
 * - fenced ```html blocks → [ChatPart.HtmlCard] (artifact title via a
 *   "title:"-comment or a title-tag; partial open fences render live above the
 *   size threshold §7.1);
 * - ```mermaid fences → a mermaid HtmlCard (pre.mermaid, code text-escaped;
 *   mermaid runs in our bundled vendor doc, securityLevel default=strict);
 * - ```chart fences (JSON config) → a chart.js canvas HtmlCard (config rides
 *   an application/json script tag — `</script`-proof; bootstrap renders it);
 * - '<details type="tool_calls" …>' blocks → [ChatPart.ToolCall] (attributes are
 *   URI-encoded, verified against the web's decodeString = decodeURIComponent;
 *   the embeds attribute carries URI-encoded HTML embed lists).
 * - math in bubbles stays RAW text: the old beacon-katex span wrapping leaked
 *   literal HTML into MiniMarkdown bubbles (span handling lives in card docs
 *   only); a compose-side math renderer is a separate follow-up.
 *
 * NOTE: the details/think markup below is assembled from string fragments on
 * purpose — raw block-level HTML tags inside these literals kept tripping tooling.
 */
object Segmenter {
    private val htmlFence = Regex("^\\s*```\\s*(html|web|frontend)\\s*$", RegexOption.IGNORE_CASE)
    private val mermaidFence = Regex("^\\s*```\\s*mermaid\\s*$", RegexOption.IGNORE_CASE)
    private val chartFence = Regex("^\\s*```\\s*(chart|chartjs)\\s*$", RegexOption.IGNORE_CASE)
    private val anyFence = Regex("^\\s*(`{3,}|~{3,})\\s*([a-zA-Z0-9_+-]*)\\s*$")
    private val detailsBlock = Regex(
        "<" + "details\\b([^>]*)>([\\s\\S]*?)</" + "details>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    /** type="reasoning" details (the web's text-mode THINKING channel). */
    private val reasoningDetails = Regex(
        "<" + "details[^>]*type=[\"']reasoning[\"'][^>]*>" + "([\\s\\S]*?)" + "</" + "details>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    /** type="code_interpreter" details — stripped from text-mode content. */
    private val codeInterpreterDetails = Regex(
        "<" + "details[^>]*type=[\"']code_interpreter[\"'][^>]*>[\\s\\S]*?</" + "details>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val titleComment = Regex("<!--\\s*title:\\s*(.+?)\\s*-->", RegexOption.IGNORE_CASE)
    private val titleTag = Regex("<" + "title[^>]*>(.*?)</" + "title>", RegexOption.IGNORE_CASE)
    private val rawHtmlBlob = Regex("<" + "div\\b[^>]*>[\\S\\n]{200,}", RegexOption.IGNORE_CASE)

    const val LIVE_PREVIEW_MIN_CHARS = 200

    fun build(contentMd: String, streaming: Boolean): List<ChatPart> {
        if (contentMd.isBlank()) {
            return if (streaming) listOf(ChatPart.Markdown("", streaming = true)) else emptyList()
        }
        val parts = ArrayList<ChatPart>(4)

        // Tool-call details blocks lift out first (chip + any embedded UI results).
        var remainder = contentMd
        detailsBlock.findAll(contentMd).forEach { match ->
            partsFromToolDetails(match).forEach { parts.add(it) }
            remainder = remainder.replace(match.value, "")
        }

        // Text-mode reasoning/code-interpreter details (web parity:
        // ContentRenderer.svelte:24-68 lifts ALL details out of the plain
        // content; reasoning renders as its own collapsible — the think-tag
        // twin — and code_interpreter's block stays server-channel-only for
        // now). Stripped here BEFORE the blob pass, so their inner markup
        // never leaks into MiniMarkdown like raw tags.
        reasoningDetails.findAll(contentMd).forEach { match ->
            val inner = match.groupValues[1].trim()
            if (inner.isNotBlank()) parts.add(ChatPart.Reasoning(inner))
            remainder = remainder.replace(match.value, "")
        }
        codeInterpreterDetails.findAll(contentMd).forEach { match ->
            remainder = remainder.replace(match.value, "")
        }

        // Raw HTML blobs (unfenced div trees) become cards (agent.md §7.1).
        rawHtmlBlob.findAll(remainder).forEach { match ->
            parts.add(
                ChatPart.HtmlCard(
                    id = "blob-" + match.value.hashCode().toUInt().toString(16),
                    html = match.value,
                    kind = CardKind.Artifact,
                    title = titleTag.find(match.value)?.groupValues?.get(1)?.trim(),
                    sandboxLevel = SandboxLevel.UI,
                    minHeightDp = 120,
                ),
            )
            remainder = remainder.replace(match.value, "")
        }

        partLines(remainder, streaming).forEach { parts.add(it) }
        if (streaming && parts.isEmpty()) {
            parts.add(ChatPart.Markdown("", streaming = true))
        }
        return parts
    }

    /** Line-walk: fences (html/mermaid/chart = cards; code stays markdown) + paragraphs. */
    private fun partLines(md: String, masterStreaming: Boolean): List<ChatPart> {
        if (md.isBlank()) return emptyList()
        val parts = ArrayList<ChatPart>(2)
        val paragraph = StringBuilder()
        var openFenceType: String? = null
        val fenceBody = StringBuilder()

        fun flushParagraph() {
            val text = paragraph.toString().trim('\n')
            if (text.isNotBlank()) parts.add(ChatPart.Markdown(text, streaming = masterStreaming))
            paragraph.setLength(0)
        }

        md.split('\n').forEach line@{ line ->
            val fenceMatch = anyFence.find(line)
            if (fenceMatch != null && openFenceType == null) {
                val lang = fenceMatch.groupValues[2].lowercase()
                openFenceType = when {
                    lang == "html" || lang == "web" || lang == "frontend" -> "html"
                    lang == "mermaid" -> "mermaid"
                    lang == "chart" || lang == "chartjs" -> "chart"
                    else -> "code"
                }
                if (openFenceType == "code") {
                    paragraph.append(line).append('\n')
                    openFenceType = "code-open"
                    return@line
                }
                flushParagraph()
                return@line
            }
            if (openFenceType != null) {
                if (fenceMatch != null) {
                    if (openFenceType == "code-open") {
                        paragraph.append(line).append('\n')
                        openFenceType = null
                        return@line
                    }
                    val body = fenceBody.toString().trim('\n')
                    when (openFenceType) {
                        "html" -> parts.add(htmlCard(body))
                        "mermaid" -> parts.add(mermaidCard(body))
                        "chart" -> parts.add(chartCard(body))
                    }
                    fenceBody.setLength(0)
                    openFenceType = null
                    return@line
                }
                fenceBody.append(line).append('\n')
                return@line
            }
            if (line.isBlank()) {
                flushParagraph()
                return@line
            }
            paragraph.append(line).append('\n')
        }

        // Open card fence mid-stream: live preview above the threshold (§7.1);
        // open code fences stay in the paragraph.
        if (openFenceType != null && openFenceType != "code-open" && fenceBody.length >= LIVE_PREVIEW_MIN_CHARS) {
            val body = fenceBody.toString().trim('\n')
            when (openFenceType) {
                "html" -> parts.add(htmlCard(body, streaming = true))
                "mermaid" -> parts.add(mermaidCard(body))
                "chart" -> parts.add(chartCard(body))
            }
        }
        flushParagraph()
        return parts
    }

    private fun htmlCard(html: String, streaming: Boolean = false): ChatPart = ChatPart.HtmlCard(
        id = "card-" + html.hashCode().toUInt().toString(16),
        html = html,
        kind = CardKind.Artifact,
        title = titleComment.find(html)?.groupValues?.get(1)
            ?: titleTag.find(html)?.groupValues?.get(1)?.trim(),
        sandboxLevel = SandboxLevel.UI,
        minHeightDp = 120,
        interactive = !streaming,
    )

    private fun mermaidCard(code: String): ChatPart = ChatPart.HtmlCard(
        id = "mermaid-" + code.hashCode().toUInt().toString(16),
        html = "<pre class=\"mermaid\">" + escapeHtml(code) + "</pre>",
        kind = CardKind.Mermaid,
        title = null,
        sandboxLevel = SandboxLevel.UI,
        minHeightDp = 140,
    )

    private fun chartCard(jsonConfig: String): ChatPart = ChatPart.HtmlCard(
        id = "chart-" + jsonConfig.hashCode().toUInt().toString(16),
        html = chartMarkup(jsonConfig),
        kind = CardKind.Chart,
        title = null,
        sandboxLevel = SandboxLevel.UI,
        minHeightDp = 220,
    )

    /**
     * chart.js canvas + config: the JSON rides an application/json script tag
     * (textContent + JSON.parse in the card bootstrap) — a model-authored
     * config containing `</script>` can never close the wrapper early.
     */
    internal fun chartMarkup(jsonConfig: String): String =
        "<div class=\"beacon-chart\"><canvas></canvas></div>\n" +
            "<script type=\"application/json\" id=\"beacon-chart-config\">" +
            jsonConfig.trim().replace("</", "<\\/") +
            "</script>"

    /** Text-escape for code that must READ as text (mermaid sources). */
    internal fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** '<' + "think">…< + "/think"> blocks → reasoning body (stored messages). */
    fun reasoningFromThinkBlocks(md: String): String? {
        val match = Regex("<" + "think" + ">([\\s\\S]*?)</" + "think" + ">").find(md) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /** '<details type="tool_calls" …>' block → ToolCall chip + HtmlCards for its embeds. */
    private fun partsFromToolDetails(match: MatchResult): List<ChatPart> {
        val attrs = attrMap(match.groupValues[1])
        val body = match.groupValues[2]
        val type = attrs["type"]?.lowercase()
        if (type != "tool_calls" && type != "tool_call") return emptyList()

        val out = ArrayList<ChatPart>(2)
        val name = attrs["title"]?.substringBefore('(')?.trim() ?: "tool"
        val callId = attrs["id"] ?: attrs["call_id"]
        val status = if (body.isBlank()) ToolStatus.Running else ToolStatus.Done
        out.add(
            ChatPart.ToolCall(
                callId = callId,
                name = name,
                args = body.trim(),
                status = status,
                result = null,
            ),
        )
        attrs["embeds"]?.let { encoded ->
            decodeHtmlList(encoded).forEach { html ->
                out.add(
                    ChatPart.HtmlCard(
                        id = "embed-" + html.hashCode().toUInt().toString(16),
                        html = html,
                        kind = CardKind.Embed,
                        title = titleTag.find(html)?.groupValues?.get(1)?.trim(),
                        // Embedded UI results are server-provenanced (tool-emitted, §11) —
                        // the web allows same-origin only when trusted; Beacon: MEDIA floor.
                        sandboxLevel = SandboxLevel.MEDIA,
                        minHeightDp = 160,
                    ),
                )
            }
        }
        return out
    }

    /** embeds attribute: URI-encoded JSON string list of HTML documents. */
    private fun decodeHtmlList(encoded: String): List<String> = try {
        val decoded = URLDecoder.decode(encoded, "UTF-8")
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(decoded)
            as? kotlinx.serialization.json.JsonArray
        arr?.mapNotNull { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
        }?.filter { it.contains('<') } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** The details-attribute grammar: key="value" pairs with URI-decoded values. */
    private fun attrMap(raw: String): Map<String, String> {
        val attrs = HashMap<String, String>()
        Regex("([a-zA-Z_:.-]+)\\s*=\\s*\"([^\"]*)\"").findAll(raw).forEach { m ->
            attrs[m.groupValues[1].lowercase()] = URLDecoder.decode(m.groupValues[2], "UTF-8")
        }
        return attrs
    }
}