package dev.beacon.core.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/**
 * Typed stream events the chat engine consumes (per-frame; the engine owns the
 * accumulated message shape — same parser/dispatch split as Conduit, no code copy).
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
     * rendering even when empty (agent.md §6.2 #5, §17).
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
 * Precedence per agent.md §6.2: envelopes → error → response.* → choices deltas →
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
        if (frame.isHeartbeat) return emptyList() // agent.md §17: heartbeats are never decoded

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
                    responseOutput.clear()
                    responseOutput.addAll(out.toList())
                    events.add(StreamEvent.OutputSnapshot(out.toList()))
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

        // 3. Responses-API passthrough (fold, don't reject — agent.md §17)
        if (type != null && type.startsWith("response.")) {
            return foldResponsesEvent(type, obj)
        }

        return foldTransportNeutral(obj)
    }

    /** Mirrors `handle_responses_streaming_event`'s primary event types (response.py:405+). */
    private fun foldResponsesEvent(type: String, obj: JsonObject): List<StreamEvent> = when (type) {
        "response.output_item.added", "response.output_item.done" -> {
            (obj["item"] as? JsonObject)?.let { item ->
                val outputIndex = (obj["output_index"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: responseOutput.size
                val existing = responseOutput.indexOfFirst { existingRaw ->
                    val existing = existingRaw as? JsonObject
                    (existing?.get("id") == item["id"] && item["id"] != JsonNull) ||
                        (
                            existing != null &&
                                item["call_id"] != JsonNull &&
                                existing["call_id"] == item["call_id"] &&
                                existing["type"] == item["type"]
                            )
                }
                when {
                    existing >= 0 -> responseOutput[existing] = item
                    outputIndex in responseOutput.indices -> responseOutput.add(outputIndex, item)
                    else -> responseOutput.add(item)
                }
                listOf(StreamEvent.OutputSnapshot(responseOutput.toList()))
            } ?: emptyList()
        }
        "response.output_text.delta" -> {
            val delta = (obj["delta"] as? JsonPrimitive)?.content
            delta?.takeIf { it.isNotEmpty() }?.let { listOf(StreamEvent.Content(it)) } ?: emptyList()
        }
        "response.reasoning_text.delta", "response.reasoning_summary_text.delta" -> {
            val delta = (obj["delta"] as? JsonPrimitive)?.content
            delta?.takeIf { it.isNotEmpty() }?.let { listOf(StreamEvent.Reasoning(it)) } ?: emptyList()
        }
        "response.completed" -> {
            val response = obj["response"] as? JsonObject
            val events = ArrayList<StreamEvent>(2)
            (response?.get("output") as? JsonArray)?.let { out ->
                responseOutput.clear()
                responseOutput.addAll(out.toList())
                events.add(StreamEvent.OutputSnapshot(out.toList()))
            }
            (response?.get("usage"))?.takeIf { it !is JsonNull }?.let {
                events.add(StreamEvent.UsageUpdate(it, null))
            }
            if (events.isEmpty()) events.add(StreamEvent.Deferred(type, response))
            events
        }
        else -> listOf(StreamEvent.Deferred(type, obj))
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

            // All three provider key spellings (middleware.py:3474; agent.md §17)
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
        (obj["output"] as? JsonArray)?.let {
            responseOutput.clear()
            responseOutput.addAll(it.toList())
            events.add(StreamEvent.OutputSnapshot(it.toList()))
        }

        // Meta fields (agent.md §6.2 #6)
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