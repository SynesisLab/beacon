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
 *   (agent.md §17); text-accumulation parts are used only when outputs never appeared;
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
                // markdown (agent.md §7.1).
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
                "message" -> {
                    val text = (item["content"] as? JsonArray).outputText()
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
                    add(
                        ChatPart.ToolCall(
                            callId = callId,
                            name = (item["name"] as? JsonPrimitive)?.content ?: "tool",
                            args = (item["arguments"] as? JsonPrimitive)?.content ?: "",
                            status = if (item.statusInProgress()) ToolStatus.Running else ToolStatus.Done,
                            result = pendingCalls[callId]?.result,
                        ),
                    )
                }
                "function_call_output" -> {
                    val callId = (item["call_id"] as? JsonPrimitive)?.content
                    val result = item["output"]
                    val asFile = displayFile.parse(result, json)
                    // display_file results become FILE parts — never JSON text (§7.1).
                    if (asFile != null) {
                        add(asFile)
                        return@forEach
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

private fun JsonObject.statusInProgress(): Boolean =
    (this["status"] as? JsonPrimitive)?.content == "in_progress"