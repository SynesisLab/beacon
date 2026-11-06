package dev.beacon.core.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * Typed stream events the chat engine consumes (per-frame; the engine owns the
 * accumulated message shape — same parser/dispatch split, no code copy).
 */
sealed interface StreamEvent {
    data class Content(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent

    /** type:status progress pill — data {content?, done?} (middleware.py:1241,1309). */
    data class Status(val text: String?, val done: Boolean?) : StreamEvent

    /** type:source / citation — raw source payload (middleware.py:3289). */
    data class SourceEmitted(val source: JsonElement) : StreamEvent

    /** type:chat:message:follow_ups — data {follow_ups: […]} (middleware.py:3630-3641). */
    data class FollowUps(val items: List<String>) : StreamEvent

    /** type:chat:title — data is the plain title string (middleware.py:3694-3715). */
    data class TitleUpdate(val title: String) : StreamEvent

    /** type:chat:tags — data is the tag list (middleware.py:3740-3747). */
    data class TagsUpdate(val tags: List<String>) : StreamEvent

    /**
     * type:context_compaction — OWUI native compaction progress
     * (utils/context_compaction.py:82-151): data {action, description, done,
     * error?, checkpoint_message_id?, context_summary?}. The web shows a
     * per-message "Compacting context" spinner, then the summary row.
     */
    data class CompactionStatus(val description: String?, val done: Boolean, val error: Boolean) : StreamEvent

    /**
     * type:terminal:display_file — the Open Terminal display_file builtin's
     * auto-open signal (middleware.py:961 terminal_event_handler; Chat.svelte
     * terminalEventHandler → displayFileHandler): data {path, page?} — the
     * FILE VIEWER takes over. Skipped server-side when inline:true,
     * displayed:true or exists:false.
     */
    data class TerminalFileDisplay(val path: String, val page: Int?) : StreamEvent

    /**
     * type:chat:list — the created chat's id (server-side chat creation on first
     * send without chat_id; main.py:304-307, middleware.py:249).
     */
    data class ChatListDiscovered(val chatId: String, val folderId: String?) : StreamEvent

    /** message.embeds — raw inline HTML tool-embed documents (middleware.py:730-840). */
    data class Embeds(val htmlList: List<String>) : StreamEvent

    /** choices[].finish_reason — distinguishes a natural stop from a client abort (§6.3). */
    data class FinishReason(val value: String) : StreamEvent

    /**
     * Authoritative output snapshot — from a top-level `output:` in the payload or a
     * `chat:completion`/`response.*` fold (utils/response.py:405+). Presence gates
     * rendering even when empty .
     */
    data class OutputSnapshot(val items: List<JsonElement>) : StreamEvent

    data class UsageUpdate(val usage: JsonElement, val timings: JsonElement?) : StreamEvent
    data class ModelSnapshot(val modelId: String?) : StreamEvent
    data class ContentSnapshot(val content: String?) : StreamEvent
    data class ErrorSurface(val message: String, val raw: JsonObject?) : StreamEvent

    /** data: [DONE] */
    data object StreamDone : StreamEvent

    /** Frames this stream fold tolerates but renders later (M2/M4 consumers). */
    data class Deferred(val type: String?, val data: JsonElement?) : StreamEvent
}

/**
 * Folds decoded SSE frames into [StreamEvent]s, mirroring:
 * - server-side fold: `update_assistant_message_from_stream` (middleware.py:3441-3524)
 *   — content/reasoning keys (`reasoning_content` | `reasoning` | `thinking`), usage/timings,
 *   finish_reason, `response.*` passthrough folding;
 * - client switch: Chat.svelte:1573-1710 — status/source/follow_ups/title/tags/
 *   chat:completion envelopes;
 * - error mid-stream frame: `data: {"error": …}` (middleware.py:6527).
 *
 * Precedence per envelopes → error → response.* → choices deltas →
 * output snapshot → meta. Malformed/unknown frames never throw — they resolve to
 * [StreamEvent.Deferred] or are dropped (heartbeat semantics).
 */
class OwuiStreamParser(
    private val json: Json = Json,
) {
    /** Responses-API folding state (`handle_responses_streaming_event` semantics; response.py:405+). */
    private val responseOutput = ArrayList<JsonElement>()

    fun reset() {
        responseOutput.clear()
    }

    fun onFrame(frame: SseFrameScanner.Frame): List<StreamEvent> {
        if (frame.isHeartbeat) return emptyList() // heartbeats are never decoded

        val data = frame.data ?: return emptyList()
        if (data == DONE_SENTINEL) return listOf(StreamEvent.StreamDone)
        if (data.isBlank()) return emptyList()

        val element = try {
            json.parseToJsonElement(data)
        } catch (e: Exception) {
            Timber.w(e, "SSE frame not JSON; dropping (len=%d)", data.length)
            return emptyList()
        }
        if (element !is JsonObject) return listOf(StreamEvent.Deferred(null, element))

        return foldObject(element)
    }

    private fun foldObject(obj: JsonObject): List<StreamEvent> {
        // 1. error frames (mid-stream failure, middleware.py:6527)
        (obj["error"] as? JsonObject)?.let { err ->
            val message = (err["message"] as? JsonPrimitive)?.content ?: "Stream error"
            return listOf(StreamEvent.ErrorSurface(message, err))
        }

        // 2. typed envelopes (event_emitter payloads)
        val type = (obj["type"] as? JsonPrimitive)?.content
        when (type) {
            "status" -> {
                val body = obj["data"]
                val text = (body as? JsonObject)?.get("content")?.primitiveOrNull()?.content
                val done = (body as? JsonObject)?.get("done")?.primitiveOrNull()?.booleanOrNull()
                return listOf(StreamEvent.Status(text, done))
            }
            "source", "citation" -> {
                val source = obj["data"]
                if (source is JsonObject && (source["type"] as? JsonPrimitive)?.content == "code_execution") {
                    return listOf(StreamEvent.Deferred(type, source))
                }
                return source?.let { listOf(StreamEvent.SourceEmitted(it)) } ?: emptyList()
            }
            "chat:message:follow_ups" -> {
                val items = ((obj["data"] as? JsonObject)?.get("follow_ups") as? JsonArray)?.stringList().orEmpty()
                return listOf(StreamEvent.FollowUps(items))
            }
            "chat:title" -> {
                val title = (obj["data"] as? JsonPrimitive)?.content
                return if (title != null) listOf(StreamEvent.TitleUpdate(title)) else emptyList()
            }
            "chat:message:embeds", "embeds" -> {
                // message.embeds: array of raw HTML / URL strings (ResponseMessage.svelte:755)
                val list = ((obj["data"] as? JsonObject)?.get("embeds") as? JsonArray)?.stringList().orEmpty()
                return if (list.isNotEmpty()) listOf(StreamEvent.Embeds(list)) else emptyList()
            }
            "chat:list" -> {
                val body = obj["data"] as? JsonObject
                val chatId = (body?.get("chat_id") as? JsonPrimitive)?.content
                return if (chatId != null) {
                    listOf(
                        StreamEvent.ChatListDiscovered(
                            chatId,
                            (body["folder_id"] as? JsonPrimitive)?.content,
                        ),
                    )
                } else {
                    emptyList()
                }
            }
            "chat:tags" -> {
                val tags = (obj["data"] as? JsonArray)?.stringList().orEmpty()
                return listOf(StreamEvent.TagsUpdate(tags))
            }
            "terminal:display_file" -> {
                // The Open Terminal display_file builtin signals "open this
                // file for the user" (middleware.py:961; the path is resolved
                // against the session cwd server-side).
                val body = obj["data"] as? JsonObject
                val path = (body?.get("path") as? JsonPrimitive)?.content
                val page = (body?.get("page") as? JsonPrimitive)?.content?.toIntOrNull()
                return if (!path.isNullOrEmpty()) {
                    listOf(StreamEvent.TerminalFileDisplay(path, page))
                } else {
                    emptyList()
                }
            }
            "context_compaction" -> {
                // Native compaction status (context_compaction.py:82-151).
                val body = obj["data"] as? JsonObject
                val description = (body?.get("description") as? JsonPrimitive)?.content
                val done = (body?.get("done") as? JsonPrimitive)?.booleanOrNull() == true
                val error = (body?.get("error") as? JsonPrimitive)?.booleanOrNull() == true
                return listOf(StreamEvent.CompactionStatus(description, done, error))
            }
            "chat:message:error" -> {
                val body = obj["data"]
                val message = (body as? JsonObject)?.get("content")?.primitiveOrNull()?.content
                    ?: "Message error"
                return listOf(StreamEvent.ErrorSurface(message, obj))
            }
            "chat:completion", "response:completion" -> {
                val body = obj["data"] as? JsonObject
                if (body == null) return listOf(StreamEvent.Deferred(type, body))
                // Batched-delta wrap (middleware.py:4720-4733): non-continuing task
                // mode flushes a raw delta under these aliases —
                // {type:'response:completion', data:{type:'response.output_text.delta', delta}}.
                // Fold the inner event when present.
                val nestedType = (body["type"] as? JsonPrimitive)?.content
                if (nestedType != null && nestedType.startsWith("response.")) {
                    return foldResponsesEvent(nestedType, body)
                }
                // Task-socket envelopes (middleware.py:4008/:4725/:6372) carry the
                // SAME keys both directions: output snapshots, done/error/usage/
                // finish_reason/selected_model_id/sources/title. The terminal
                // emission is {done: true, output, title, usage} (middleware.py:6341-6379).
                val events = ArrayList<StreamEvent>(4)
                val err = body["error"]
                if (err is JsonObject) {
                    val message = (err["message"] as? JsonPrimitive)?.content ?: "Completion error"
                    events.add(StreamEvent.ErrorSurface(message, err))
                }
                (body["output"] as? JsonArray)?.let { out ->
                    // MERGE (per response.completed):
                    // snapshots accumulate BY ID (+call_id+type) so tool-call
                    // rounds and reasoning survive; never clear+replace.
                    val merged = mergeOutput(responseOutput.toList(), out.toList())
                    responseOutput.clear()
                    responseOutput.addAll(merged)
                    events.add(StreamEvent.OutputSnapshot(merged))
                }
                (body["content"])?.primitiveOrNull()?.content?.takeIf { it.isNotEmpty() }
                    ?.let { events.add(StreamEvent.ContentSnapshot(it)) }
                val reasoning = (
                    body["reasoning_content"]
                        ?: body["reasoning"]
                        ?: body["thinking"]
                    )?.primitiveOrNull()?.content
                if (!reasoning.isNullOrEmpty()) events.add(StreamEvent.Reasoning(reasoning))
                body["usage"]?.takeIf { it !is JsonNull }?.let {
                    events.add(StreamEvent.UsageUpdate(it, null))
                }
                (body["selected_model_id"] as? JsonPrimitive)?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { events.add(StreamEvent.ModelSnapshot(it)) }
                (body["finish_reason"] as? JsonPrimitive)?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { events.add(StreamEvent.FinishReason(it)) }
                (body["sources"] as? JsonArray)?.let { events.add(StreamEvent.SourceEmitted(it)) }
                (body["title"] as? JsonPrimitive)?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { events.add(StreamEvent.TitleUpdate(it)) }
                if ((body["done"] as? JsonPrimitive)?.booleanOrNull() == true) {
                    events.add(StreamEvent.StreamDone)
                }
                if (events.isEmpty()) events.add(StreamEvent.Deferred(type, body))
                return events
            }
        }

        // 3. Responses-API passthrough (fold, don't reject —)
        if (type != null && type.startsWith("response.")) {
            return foldResponsesEvent(type, obj)
        }

        return foldTransportNeutral(obj)
    }

    /**
     * output accumulator: per-token deltas MUTATE the item in
     * place and every mutation emits a fresh Snapshot, so one list renders
     * everything — text, reasoning, tool calls — as it grows. Snapshots MERGE
     * by id (+call_id+same-type: a function_call_output shares the call's
     * call_id and must never erase the call — issue #751's vanish).
     */
    private fun foldResponsesEvent(type: String, obj: JsonObject): List<StreamEvent> {
        val itemId = itemId(obj)

        // Structural: the round's items added/replaced in place.
        if (type == "response.output_item.added" || type == "response.output_item.done") {
            val item = obj["item"] as? JsonObject
            if (item != null) {
                val found = findItem(responseOutput, item)
                val slot = if (found >= 0) found else outputIndex(obj) ?: responseOutput.size
                if (slot >= responseOutput.size) responseOutput.add(item)
                else responseOutput[slot] = item
            }
            return listOf(StreamEvent.OutputSnapshot(responseOutput.toList()))
        }

        if (type.endsWith(".delta") || type.endsWith(".done")) {
            // "response.<family>.<suffix>" — the item family names the target.
            val family = type.removePrefix("response.").substringBefore('.')
            val slot = when {
                itemId != null -> {
                    val found = responseOutput.indexOfFirst { existing ->
                        (existing as? JsonObject)?.let { o ->
                            o["id"]?.primitiveOrNull()?.content == itemId ||
                                o["call_id"]?.primitiveOrNull()?.content == itemId
                        } == true
                    }
                    if (found >= 0) found else outputIndex(obj) ?: responseOutput.size
                }
                outputIndex(obj) != null -> outputIndex(obj)!!
                // Id-less deltas continue the LAST item when it matches the family.
                else -> {
                    val last = responseOutput.lastOrNull() as? JsonObject
                    if (last != null && familyMatchesItem(family, last)) responseOutput.size - 1
                    else responseOutput.size
                }
            }
            ensureItemAt(responseOutput, slot, family)
            (responseOutput.getOrNull(slot) as? JsonObject)?.let { item ->
                responseOutput[slot] = applyDeltaToItem(type, family, item, obj)
            }
            stampReasoningTiming(responseOutput)
            // Every mutation = a fresh snapshot: one truth list, the live paint.
            return listOf(StreamEvent.OutputSnapshot(responseOutput.toList()))
        }

        if (type == "response.completed" || type == "response.failed" || type == "response.incomplete") {
            val events = ArrayList<StreamEvent>(2)
            val response = obj["response"] as? JsonObject
            (response?.get("output") as? JsonArray)?.let { out ->
                val merged = mergeOutput(responseOutput.toList(), out.toList())
                responseOutput.clear()
                responseOutput.addAll(merged)
                events.add(StreamEvent.OutputSnapshot(merged))
            }
            (response?.get("usage"))?.takeIf { it !is JsonNull }?.let {
                events.add(StreamEvent.UsageUpdate(it, null))
            }
            if (events.isEmpty()) events.add(StreamEvent.Deferred(type, response))
            return events
        }

        return listOf(StreamEvent.Deferred(type, obj))
    }

    /** The event family names its item type ("output_text" ⇒ message). */
    private fun familyMatchesItem(family: String, item: JsonObject): Boolean {
        val type = (item["type"] as? JsonPrimitive)?.content ?: return false
        return when {
            family.startsWith("reasoning") -> type == "reasoning"
            family.startsWith("function_call") -> type == "function_call"
            else -> type == "message"
        }
    }

    /** The (possibly absent) item id from a responses event. */
    private fun itemId(obj: JsonObject): String? =
        (obj["item_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    private fun outputIndex(obj: JsonObject): Int? =
        (obj["output_index"] as? JsonPrimitive)?.content?.toIntOrNull()

    /**
     * The slot an incoming ITEM updates: same id, or same call_id AND type — a
     * function_call_output shares the call's call_id and must never replace
     * the call (the web checks the type too).
     */
    private fun findItem(output: List<JsonElement>, item: JsonObject): Int {
        val id = item["id"]?.primitiveOrNull()?.content
        val callId = item["call_id"]?.primitiveOrNull()?.content
        return output.indexOfFirst { existing ->
            val o = existing as? JsonObject ?: return@indexOfFirst false
            (id != null && id.isNotEmpty() && o["id"]?.primitiveOrNull()?.content == id) ||
                (callId != null && callId.isNotEmpty() &&
                    o["call_id"]?.primitiveOrNull()?.content == callId &&
                    o["type"] == item["type"])
        }
    }

    /** Grows the output to [toIndex] in place: the addressed slot = the
     *  family's item, the gaps = neutral message fillers (no ids). */
    private fun ensureItemAt(output: ArrayList<JsonElement>, toIndex: Int, family: String) {
        val kind = when {
            family.startsWith("reasoning") -> "reasoning"
            family == "function_call" || family == "function_call_arguments" -> "function_call"
            else -> "message"
        }
        while (output.size <= toIndex) {
            val last = output.size == toIndex
            output.add(
                buildJsonObject {
                    if (last) {
                        put("type", kind)
                        put("status", "in_progress")
                        put("role", "assistant")
                        put("content", JsonArray(emptyList()))
                        if (kind == "function_call") put("name", "tool")
                    } else {
                        put("type", "message")
                        put("status", "in_progress")
                        put("role", "assistant")
                        put("content", JsonArray(emptyList()))
                    }
                },
            )
        }
    }

    /** The reasoning timing (client-side stamping):
     *  the addressed slot's item mutates in the caller's list. JsonObjects are
     *  immutable, so the stamped copy replaces the slot. */
    private fun stampReasoningTiming(output: MutableList<JsonElement>) {
        val now = System.currentTimeMillis() / 1000.0
        for (i in output.indices) {
            val item = output[i] as? JsonObject ?: continue
            if ((item["type"] as? JsonPrimitive)?.content != "reasoning") continue
            val started = (item["started_at"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val updated = HashMap(item)
            var touched = false
            if (started == null || started <= 0.0) {
                updated["started_at"] = JsonPrimitive(now)
                touched = true
            } else if (i != output.size - 1 && item["duration"] == null) {
                updated["ended_at"] = JsonPrimitive(now)
                updated["duration"] = JsonPrimitive((now - started).toInt().coerceAtLeast(0))
                touched = true
            }
            if (touched) output[i] = JsonObject(updated)
        }
    }

    /** One delta/done event folded INTO a NEW item (JsonObjects are immutable;
     *  the caller replaces the slot). Returns the updated item. */
    private fun applyDeltaToItem(type: String, family: String, item: JsonObject, event: JsonObject): JsonObject {
        val delta = event["delta"]?.primitiveOrNull()?.content ?: ""
        val slot = contentIndex(event)
        val next = when {
            family == "function_call_arguments" ->
                JsonObject(item.toMap() + ("arguments" to JsonPrimitive(
                    (item["arguments"]?.primitiveOrNull()?.content ?: "") + delta,
                )))
            family == "reasoning_summary_part" || family == "content_part" -> item
            type.endsWith(".done") -> {
                val doneText = (event["text"] ?: event["delta"])?.primitiveOrNull()?.content
                if (!doneText.isNullOrEmpty()) withItemText(item, doneText, slot) else item
            }
            // output_text / reasoning_text / reasoning_summary_text: append.
            else -> withItemText(item, (itemText(item, slot) ?: "") + delta, slot)
        }
        return JsonObject(next.toMap() + ("status" to JsonPrimitive("in_progress")))
    }

    private fun contentIndex(event: JsonObject): Int =
        (event["content_index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    private fun itemText(item: JsonObject, slot: Int): String? =
        (item["content"] as? JsonArray)
            ?.toList()
            ?.getOrNull(slot)
            ?.let { part -> (part as? JsonObject)?.get("text")?.primitiveOrNull()?.content }

    private fun withItemText(item: JsonObject, text: String, index: Int): JsonObject {
        val parts = ((item["content"] as? JsonArray)?.toList() ?: emptyList()).toMutableList()
        while (parts.size <= index) {
            parts.add(buildJsonObject { put("type", "output_text"); put("text", "") })
        }
        val existing = (parts[index] as? JsonObject) ?: buildJsonObject { put("type", "output_text") }
        parts[index] = JsonObject(existing.toMap() + ("text" to JsonPrimitive(text)))
        return JsonObject(item.toMap() + ("content" to JsonArray(parts)))
    }

    /**
     * Snapshot MERGE (response.completed): known items
     * replace in place (id, or call_id+same-type), new items append; previous
     * items the snapshot doesn't carry SURVIVE (earlier tool rounds).
     */
    private fun mergeOutput(previous: List<JsonElement>, incoming: List<JsonElement>): List<JsonElement> {
        val merged = previous.toMutableList()
        for (raw in incoming) {
            val item = raw as? JsonObject ?: continue
            val index = findItem(merged, item)
            if (index >= 0) merged[index] = item else merged.add(item)
        }
        stampReasoningTiming(merged)
        return merged
    }

    /** OpenAI-style transport frames + meta fields (choices/output/usage/sources/content). */
    private fun foldTransportNeutral(obj: JsonObject): List<StreamEvent> {
        val events = ArrayList<StreamEvent>(4)

        val choices = obj["choices"] as? JsonArray
        for (choiceRaw in choices ?: emptyList()) {
            val choice = choiceRaw as? JsonObject ?: continue
            (choice["finish_reason"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotEmpty() }
                ?.let { events.add(StreamEvent.FinishReason(it)) }
            val message = choice["message"] as? JsonObject // non-streaming jsonCompletion shape
            val body = (choice["delta"] as? JsonObject) ?: message ?: continue

            val content = body["content"]?.primitiveOrNull()?.content
            if (!content.isNullOrEmpty()) {
                events.add(StreamEvent.Content(content))
            }

            // All three provider key spellings (middleware.py:3474;)
            val reasoning = (
                body["reasoning_content"]
                    ?: body["reasoning"]
                    ?: body["thinking"]
                )?.primitiveOrNull()?.content
            if (!reasoning.isNullOrEmpty()) {
                events.add(StreamEvent.Reasoning(reasoning))
            }
        }

        // Top-level output snapshot is authoritative even when empty (§17)
        (obj["output"] as? JsonArray)?.let { out ->
            val merged = mergeOutput(responseOutput.toList(), out.toList())
            responseOutput.clear()
            responseOutput.addAll(merged)
            events.add(StreamEvent.OutputSnapshot(merged))
        }

        // Meta fields
        (obj["usage"])?.takeIf { it !is JsonNull }?.let { usage ->
            events.add(StreamEvent.UsageUpdate(usage, obj["timings"]))
        }
        (obj["selected_model_id"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotEmpty() }
            ?.let { events.add(StreamEvent.ModelSnapshot(it)) }
        ((obj["sources"])?.takeIf { it !is JsonNull })?.let {
            events.add(StreamEvent.SourceEmitted(it))
        }
        (obj["content"] as? JsonPrimitive)?.content?.let {
            events.add(StreamEvent.ContentSnapshot(it))
        }

        // Frame with none of the recognized shapes: tolerate.
        if (events.isEmpty()) {
            Timber.w("Unrecognized SSE frame; deferring: keys=%s", obj.keys)
            events.add(StreamEvent.Deferred(null, obj))
        }

        return events
    }

    private fun JsonElement?.primitiveOrNull() = this as? JsonPrimitive

    private fun JsonPrimitive.booleanOrNull(): Boolean? = when (content) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun JsonArray?.stringList(): List<String> =
        (this ?: return emptyList()).mapNotNull { (it as? JsonPrimitive)?.content }

    companion object {
        const val DONE_SENTINEL = "[DONE]"
    }
}