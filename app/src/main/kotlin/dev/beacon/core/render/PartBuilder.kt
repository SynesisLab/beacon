package dev.beacon.core.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Accumulated fold state (the engine mirrors the server-side canonical fold)
 * → the flat [ChatPart] list the UI renders.
 *
 * Rules (verified, docs/protocol-notes.md M1/M2 tables):
 * - `output[]` is authoritative when present — even [present-and-empty gate][FoldedMessage.outputSeen]
 *   ; text-accumulation parts are used only when outputs never appeared;
 * - message items carry output_text parts; reasoning items carry their own;
 * - function_call + function_call_output pair by call_id into a single ToolCall;
 * - display_file outputs become [ChatPart.File]s — never JSON text (§7.1);
 * - status renders as a transient pill at the top; citations/follow-ups at the bottom.
 */
data class FoldedMessage(
    val contentMd: String = "",
    val reasoningMd: String? = null,
    /** True while reasoning deltas are the last thing this fold saw (spinner). */
    val reasoningActive: Boolean = false,
    val outputItems: List<JsonElement> = emptyList(),
    /** Has the fold SEEN an output snapshot at all (empty list counts — §17)? */
    val outputSeen: Boolean = false,
    val statusText: String? = null,
    val statusDone: Boolean = true,
    val sourceCount: Int = 0,
    val followUps: List<String> = emptyList(),
    /** message.embeds — inline HTML tool documents (middleware.py:752-840). */
    val embeds: List<String> = emptyList(),
    val streaming: Boolean = false,
)

object PartBuilder {
    private val displayFile = DisplayFile

    fun build(m: FoldedMessage, json: Json = Json): List<ChatPart> = buildList {
        if (!m.statusText.isNullOrBlank()) {
            add(ChatPart.Status(m.statusText, m.statusDone))
        }

        if (m.outputSeen) {
            buildFromOutputs(m.outputItems, m.streaming, json).forEach { add(it) }
        } else {
            m.reasoningMd?.takeIf { it.isNotBlank() }?.let {
                // The Thinking spinner runs only while reasoning deltas are the
                // last thing seen — it stops when content starts (user report:
                // "thinking is not stopping"). The final content stage is NOT
                // thinking.
                add(
                    ChatPart.Reasoning(
                        it,
                        streaming = m.streaming && m.reasoningActive,
                    ),
                )
            }
            val rendered = m.contentMd.trim('\n')
            if (rendered.isNotBlank()) {
                // The segmenter splits cards/mermaid/chart/tool-details out of the
                // markdown .
                Segmenter.build(rendered, m.streaming).forEach { add(it) }
            } else if (m.streaming) {
                add(ChatPart.Markdown("", streaming = true))
            }
        }

        m.embeds.forEach { html ->
            add(
                ChatPart.HtmlCard(
                    id = "embed-" + html.hashCode().toUInt().toString(16),
                    html = html,
                    kind = CardKind.Embed,
                    title = null,
                    // Tool-provided embeds are server-provenanced (§11) → MEDIA floor.
                    sandboxLevel = SandboxLevel.MEDIA,
                    minHeightDp = 160,
                ),
            )
        }

        if (m.sourceCount > 0) {
            add(ChatPart.Citations(items = emptyList(), reportedCount = m.sourceCount))
        }
        if (m.followUps.isNotEmpty()) {
            add(ChatPart.FollowUps(m.followUps))
        }
    }

    private fun buildFromOutputs(
        items: List<JsonElement>,
        streaming: Boolean,
        json: Json,
    ): List<ChatPart> = buildList {
        val pendingCalls = HashMap<String, ChatPart.ToolCall>()
        /** call_ids already emitted as Visualization cards (their outputs must
         *  not fall through into junk tool rows). */
        val vizDone = HashSet<String>()
        /** Visualization calls still pending (a ToolCall row stands in until
         *  the accepted output replaces it). */
        val vizPending = HashSet<String>()

        items.forEach { raw ->
            val item = raw as? JsonObject ?: return@forEach
            when ((item["type"] as? JsonPrimitive)?.content) {
                "reasoning" -> {
                    val text = (item["content"] as? JsonArray).outputText()
                    if (text.isNotBlank()) {
                        // The item's own status is the authority: NOT the whole
                        // message's streaming flag (which stays true through the
                        // final content phase — "thinking is not stopping").
                        add(ChatPart.Reasoning(text, streaming = item.statusInProgress()))
                    }
                }
                "open_webui:context_compaction" -> {
                    // middleware.py:6046 — the checkpoint summary rowed into the
                    // output (web: displayItems context_compaction row).
                    val summary = (item["summary"] as? JsonPrimitive)?.content.orEmpty()
                    add(ChatPart.Compaction(summary))
                }
                "message" -> {
                    val text = (item["content"] as? JsonArray).outputText()
                    // message.embeds (middleware.py:3310): inline UI docs when
                    // the server rows embeds onto the message item itself.
                    (item["embeds"] as? JsonArray)
                        ?.mapNotNull { part -> (part as? JsonPrimitive)?.takeIf { it.isString }?.content }
                        ?.forEach { embedHtml ->
                            add(
                                ChatPart.HtmlCard(
                                    id = "embed-" + embedHtml.hashCode().toUInt().toString(16),
                                    html = embedHtml,
                                    kind = CardKind.Embed,
                                    title = null,
                                    sandboxLevel = SandboxLevel.MEDIA,
                                    minHeightDp = 160,
                                ),
                            )
                        }
                    // Blank until the first delta; the streaming caret covers the gap.
                    if (text.isNotBlank() || item.statusInProgress()) {
                        add(ChatPart.Markdown(text, streaming = item.statusInProgress()))
                    }
                }
                "file" -> {
                    // Server-converted display_file items (ToolOutputFile on the web,
                    // middleware.py:325-345 for terminal / builtin.py for instance files).
                    displayFile.fromObject(item)?.let { add(it) }
                }
                "function_call" -> {
                    val callId = (item["call_id"] as? JsonPrimitive)?.content
                        ?: (item["id"] as? JsonPrimitive)?.content
                    val name = (item["name"] as? JsonPrimitive)?.content ?: "tool"
                    val statusText = (item["status"] as? JsonPrimitive)?.content
                    val pending = item.statusInProgress() || statusText == "pending"
                    // Web parity (structuredOutput.ts:407-413): a pending /
                    // in-progress ask_user never shows as a chip — the
                    // interactive dialog IS its display.
                    if (name == "ask_user" && pending) {
                        return@forEach
                    }
                    // The visualization built-in tool (tools/visualizations.py):
                    // render_visualization {title, html, libraries?}[= or via the
                    // builtin:call_tool dispatcher, below] → a full-bleed card,
                    // never a JSON-dump tool row (web VisualizationToolCall.svelte).
                    if (item.isVisualizationCall()) {
                        val card = visualizationCard(callId, pending) {
                            visualizationArgsFromCall(item)
                        }
                        if (card != null) {
                            callId?.let { vizDone.add(it) }
                            add(card)
                            return@forEach
                        }
                        callId?.let { vizPending.add(it) }
                    }
                    add(
                        ChatPart.ToolCall(
                            callId = callId,
                            name = name,
                            args = (item["arguments"] as? JsonPrimitive)?.content ?: "",
                            status = if (pending) ToolStatus.Running else ToolStatus.Done,
                            result = pendingCalls[callId]?.result,
                        ),
                    )
                }
                "function_call_output" -> {
                    // THE app-frame channel (verified in chat 6fc19ae0's stored
                    // outputJson, 2026-11-04): tools returning an inline
                    // HTMLResponse (Frontier UI, MCP frames) put full HTML
                    // documents on the function_call_output item as a SIBLING
                    // of `output` — middleware.py:745-752 → the responses
                    // pipeline rows them through (middleware.py:5844-5850).
                    (item["embeds"] as? JsonArray)
                        ?.mapNotNull { part -> (part as? JsonPrimitive)?.takeIf { it.isString }?.content }
                        ?.forEach { embedHtml ->
                            add(
                                ChatPart.HtmlCard(
                                    id = "embed-" + embedHtml.hashCode().toUInt().toString(16),
                                    html = embedHtml,
                                    kind = CardKind.Embed,
                                    title = null,
                                    // Server-provenanced (tool-emitted, §11) → MEDIA floor.
                                    sandboxLevel = SandboxLevel.MEDIA,
                                    minHeightDp = 160,
                                ),
                            )
                        }
                    val callId = (item["call_id"] as? JsonPrimitive)?.content
                    // OpenAI content-parts wrapper: [{type:'input_text',text:"…"}]
                    // → the joined text (seen on-device; DisplayFile handles the
                    // file leg the same way).
                    val result = unwrapInputTextParts(item["output"])
                    val asFile = displayFile.parse(result, json)
                    // display_file results become FILE parts — never JSON text (§7.1).
                    if (asFile != null) {
                        add(asFile)
                        return@forEach
                    }
                    // A completed visualization call's card replaced its pending
                    // ToolCall row the moment the args fully arrived; the
                    // {'status':'accepted'} output never becomes a junk row.
                    if (callId != null && (callId in vizDone || callId in vizPending)) {
                        val index = indexOfFirst { (it as? ChatPart.ToolCall)?.callId == callId }
                        if (index >= 0) {
                            val call = get(index) as ChatPart.ToolCall
                            visualizationCard(callId, pending = false) {
                                visualizationArgsFromRawCall(call.args)
                            }?.let { card ->
                                set(index, card)
                                vizPending.remove(callId)
                                vizDone.add(callId)
                                return@forEach
                            }
                        } else {
                            return@forEach
                        }
                    }
                    // Attach the result onto the matching call part already collected.
                    val index = indexOfFirst { (it as? ChatPart.ToolCall)?.callId == callId }
                    if (index >= 0 && callId != null) {
                        val call = get(index) as ChatPart.ToolCall
                        set(index, call.copy(result = result))
                    } else if (callId != null) {
                        pendingCalls[callId] = ChatPart.ToolCall(
                            callId = callId,
                            name = "tool",
                            args = "",
                            status = ToolStatus.Done,
                            result = result,
                        )
                        add(pendingCalls[callId]!!)
                    }
                }
                else -> Unit // unknown output item types: tolerated (M2 widening)
            }
        }
    }
}

/** output_text parts join; non-text parts ignored. */
private fun JsonArray?.outputText(): String =
    (this ?: return "").toList().joinToString("") { part ->
        val obj = part as? JsonObject ?: return@joinToString ""
        if ((obj["type"] as? JsonPrimitive)?.content == "output_text") {
            (obj["text"] as? JsonPrimitive)?.content.orEmpty()
        } else {
            ""
        }
    }

/**
 * function_call_output results may arrive wrapped in an OpenAI content-parts
 * array (`[{type:'input_text', text:"…"}]`) — unwrap to the joined text so
 * the tool chip reads prose, not the wrapper. Anything else passes unchanged.
 */
internal fun unwrapInputTextParts(raw: JsonElement?): JsonElement {
    if (raw == null) return kotlinx.serialization.json.JsonNull
    val array = raw as? JsonArray ?: return raw
    val parts = array.toList()
    if (parts.isEmpty()) return array
    val allText = parts.all { part ->
        (part as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.content == "input_text" } == true
    }
    if (!allText) return array
    return JsonPrimitive(parts.joinToString("") { part ->
        ((part as JsonObject)["text"] as? JsonPrimitive)?.content.orEmpty()
    })
}

private fun JsonObject.statusInProgress(): Boolean =
    (this["status"] as? JsonPrimitive)?.content == "in_progress"

// ── Visualization built-in tool (render_visualization) ──────────────────────

/** Parsed {title, html, libraries?} arguments (parseVisualizationArguments parity,
 *  src/lib/utils/visualizations/arguments.ts:11-31). */
internal data class VisualizationArgs(val title: String, val html: String, val libraries: List<String>)

/**
 * Decodes a visualization call's arguments (string OR object form). Tolerant:
 * partial/streaming JSON returns null — the tool-call row stands in until the
 * full arguments snapshot arrives (the web's createStringFieldDecoder does the
 * progressive decode for its live preview; Beacon renders once complete).
 */
internal fun visualizationArgsFromCall(item: JsonObject): VisualizationArgs? {
    val raw = when (val args = item["arguments"]) {
        is JsonPrimitive -> args.content
        is JsonObject, is JsonArray -> args.toString()
        else -> null
    } ?: return null
    return visualizationArgsFromRawCall(raw)
}

/** Same parse from an already-extracted raw arguments string (used when only
 *  a stored ToolCall row survives, e.g. in the function_call_output branch). */
internal fun visualizationArgsFromRawCall(raw: String): VisualizationArgs? {
    visualizationArgsFromRaw(raw)?.let { return it }
    // The batched builtin dispatcher (dispatch.ts): arguments =
    // {"calls":[{tool_name:'render_visualization', params:{…}|"<json>"}]} —
    // use the first visualization entry's params.
    val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val calls = root["calls"] as? JsonArray ?: return null
    for (entry in calls) {
        val call = entry as? JsonObject ?: continue
        if ((call["tool_name"] as? JsonPrimitive)?.content != "render_visualization") continue
        val params = call["params"] ?: continue
        val paramsRaw = (params as? JsonPrimitive)?.content ?: params.toString()
        return visualizationArgsFromRaw(paramsRaw)
    }
    return null
}

internal fun visualizationArgsFromRaw(raw: String): VisualizationArgs? {
    if (raw.isBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val title = (obj["title"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val html = (obj["html"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
    if (html.length > 256 * 1024) return null
    val libraries = (obj["libraries"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        .orEmpty()
    if (libraries.any { it != "chartjs" }) return null
    return VisualizationArgs(title, html, libraries)
}

/** The tool's identity, both wire forms (dispatch.ts): a direct
 *  `render_visualization` call, or the `builtin:call_tool` batch dispatcher. */
internal fun JsonObject.isVisualizationCall(): Boolean {
    if ((this["name"] as? JsonPrimitive)?.content == "render_visualization") return true
    val toolId = (this["tool_id"] as? JsonPrimitive)?.content
    if (toolId == "builtin:render_visualization") return true
    if (toolId == "builtin:call_tool") {
        val args = (this["arguments"] as? JsonPrimitive)?.content ?: return false
        return args.contains("render_visualization")
    }
    return false
}

/**
 * The call → its card: only for COMPLETED accepted calls with fully-arrived
 * args (pending/partial → null, the tool-call row stands in meanwhile).
 */
internal fun visualizationCard(
    callId: String?,
    pending: Boolean,
    args: () -> VisualizationArgs?,
): ChatPart.HtmlCard? {
    if (pending || callId.isNullOrBlank()) return null
    val parsed = args() ?: return null
    return ChatPart.HtmlCard(
        id = "viz-" + callId,
        html = parsed.html,
        title = parsed.title,
        kind = CardKind.Visualization,
        libraries = parsed.libraries,
    )
}
