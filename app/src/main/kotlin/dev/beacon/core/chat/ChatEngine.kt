package dev.beacon.core.chat

import dev.beacon.core.api.ChatsApi
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.OwuiApiException
import dev.beacon.core.data.local.MessageEntity
import dev.beacon.core.render.ChatPart
import dev.beacon.core.render.FoldedMessage
import dev.beacon.core.render.PartBuilder
import dev.beacon.core.sse.OwuiStreamParser
import dev.beacon.core.sse.SseFrameScanner
import dev.beacon.core.sse.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import java.util.UUID
import javax.inject.Inject

private const val CONTENT_TYPE = "Content-Type"
private const val JSON_TYPE = "application/json; charset=utf-8"

/**
 * The chat engine — the heart of Beacon (agent.md §4): owns transport + fold +
 * persistence wiring behind intents (`send`, `stop`); UI consumes immutable
 * [ChatUiModel]. One instance per open chat session; the store keeps Room as
 * the single render source (§10).
 *
 * Stream semantics verified in docs/protocol-notes.md (M1 table):
 * - a first send carries no chat_id; the server creates the chat and reveals
 *   its id in a `chat:list` frame (main.py:304-307) — nodes persist then;
 * - envelopes (status/source/follow_ups/title/tags/list) ride the SSE stream
 *   in the HTTP-only path (middleware.py:6476 events loop);
 * - content/reasoning deltas fold exactly like the server-side canonical fold
 *   (middleware.py:3441-3524); output[] snapshots are authoritative (§17);
 * - on [DONE]: write-through + POST /api/chat/completed (§6.3), adopting only
 *   id-bearing message objects (blind projection merges destroy the tree).
 */
class ChatEngine(
    private val api: OwuiApi,
    private val store: ChatStore,
    private val json: Json,
) {
    data class RenderMessage(
        val id: String,
        val role: String,
        val markdown: String = "",
        val reasoning: String? = null,
        val statusText: String? = null,
        val pending: Boolean = false,
        val sourceCount: Int = 0,
        val followUps: List<String> = emptyList(),
        /** Render-part list (§4 contract) — the UI renders ONLY through this. */
        val parts: List<ChatPart> = emptyList(),
    )

    data class ChatUiModel(
        val chatId: String? = null,
        val messages: List<RenderMessage> = emptyList(),
        val streamingMessageId: String? = null,
        val statusText: String? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val usage: JsonElement? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ChatUiModel())
    val state: StateFlow<ChatUiModel> = _state.asStateFlow()

    private var currentCall: Call? = null
    private var streamJob: Job? = null

    // Stream-local accumulation (reset per send; mirrors the server fold)
    private var scanner = SseFrameScanner()
    private var assistantContent = StringBuilder()
    private var assistantReasoning = StringBuilder()
    private var outputAdopted = false
    private var outputSeen = false
    private var outItems: List<JsonElement> = emptyList()
    private var embedsHtml: List<String> = emptyList()
    private var lastRoomWrite = 0L
    private var activeModelId: String? = null
    private var projectionSnapshot: List<ProjectionMessage> = emptyList()

    /** Entry: adopt the server chat tree and paint Room-first (§6.4). */
    suspend fun load(existingChatId: String?) {
        if (existingChatId == null) {
            _state.value = ChatUiModel()
            return
        }
        try {
            if (store.refreshServerChat(existingChatId)) {
                paintFromStore(existingChatId)
            } else {
                _state.value = ChatUiModel(chatId = existingChatId, error = "Instance unreachable")
            }
        } catch (e: OwuiApiException) {
            _state.value = ChatUiModel(chatId = existingChatId, error = describe(e))
        }
    }

    suspend fun paintFromStore(chatId: String) {
        _state.value = ChatUiModel(
            chatId = chatId,
            messages = store.activePath(chatId).map(::renderOf),
        )
    }

    private fun renderOf(entity: MessageEntity): RenderMessage {
        val outElement = entity.outputJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val outputItems = (outElement as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()
        val base = RenderMessage(
            id = entity.messageId,
            role = entity.role,
            markdown = entity.content.orEmpty(),
            reasoning = entity.reasoningText(json),
            pending = !entity.done,
        )
        return base.copy(
            parts = PartBuilder.build(
                FoldedMessage(
                    contentMd = entity.content.orEmpty(),
                    outputItems = outputItems,
                    outputSeen = outputItems.isNotEmpty(),
                    streaming = !entity.done,
                ),
            ),
        )
    }

    /** Recomputes the streaming assistant's part list from accumulated fold state. */
    private fun foldPartsFor(id: String, transform: (RenderMessage) -> RenderMessage) =
        patch { copy(messages = messages.map { current ->
            if (current.id == id) {
                val next = transform(current)
                next.copy(
                    parts = PartBuilder.build(
                        FoldedMessage(
                            contentMd = assistantContent.toString(),
                            reasoningMd = assistantReasoning.toString().takeIf { it.isNotEmpty() },
                            outputItems = outItems,
                            outputSeen = outputSeen,
                            sourceCount = next.sourceCount,
                            followUps = next.followUps,
                            embeds = embedsHtml,
                            streaming = true,
                        ),
                    ),
                )
            } else {
                current
            }
        }) }

    /** Intent: send [text] with [modelId]; creates the chat server-side when fresh. */
    fun send(text: String, modelId: String) {
        if (text.isBlank() || _state.value.busy) return
        streamJob = scope.launch {
            try {
                streamSend(text, modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = describe(e),
                    busy = false,
                    streamingMessageId = null,
                )
            }
        }
    }

    /** Intent: stop (§6.3) — abort the transfer, user-level stop route, mark interrupted. */
    fun stop() {
        currentCall?.cancel()
        streamJob?.cancel()
        val chatId = _state.value.chatId
        val id = _state.value.streamingMessageId
        scope.launch {
            if (chatId != null) {
                runCatching { api.stopTasksForChat(chatId) }
            }
            if (id != null && chatId != null) {
                store.setDone(id, done = true)
            }
            _state.value = _state.value.copy(
                streamingMessageId = null,
                busy = false,
                statusText = null,
            )
        }
    }

    fun close() {
        currentCall?.cancel()
        streamJob?.cancel()
        scope.coroutineContext.cancelChildren()
    }

    // ── send pipeline ──────────────────────────────────────────────────────

    private suspend fun streamSend(text: String, modelId: String) {
        val existingChatId = _state.value.chatId
        val isNewChat = existingChatId == null
        val userMessageId = UUID.randomUUID().toString()
        val assistantMessageId = UUID.randomUUID().toString()
        val previousAssistantId = _state.value.messages.lastOrNull { it.role == MessageEntity.ROLE_ASSISTANT }?.id
        val now = System.currentTimeMillis()

        // Optimistic UI state first (the assistant placeholder streams locally)
        _state.value = _state.value.copy(
            busy = true,
            error = null,
            streamingMessageId = assistantMessageId,
            messages = _state.value.messages +
                RenderMessage(userMessageId, MessageEntity.ROLE_USER, markdown = text) +
                RenderMessage(assistantMessageId, MessageEntity.ROLE_ASSISTANT),
        )

        // Room write-through when the chat already exists locally; a fresh chat
        // persists its nodes once the server reveals the id (chat:list frame).
        if (!isNewChat) {
            store.insertNode(
                MessageEntity(
                    messageId = userMessageId,
                    chatId = existingChatId!!,
                    parentId = previousAssistantId,
                    role = MessageEntity.ROLE_USER,
                    content = text,
                    timestamp = now,
                ),
            )
            store.insertNode(
                MessageEntity(
                    messageId = assistantMessageId,
                    chatId = existingChatId,
                    parentId = userMessageId,
                    role = MessageEntity.ROLE_ASSISTANT,
                    content = "",
                    done = false,
                    timestamp = now,
                ),
            )
            store.setCurrentMessage(existingChatId, assistantMessageId)
        }

        assistantContent = StringBuilder()
        assistantReasoning = StringBuilder()
        outputAdopted = false
        outputSeen = false
        outItems = emptyList()
        embedsHtml = emptyList()
        lastRoomWrite = now
        scanner = SseFrameScanner()

        val projection = _state.value.messages
            .filterNot { it.id == assistantMessageId && it.role == MessageEntity.ROLE_ASSISTANT }
            .map {
                if (it.role == MessageEntity.ROLE_USER) {
                    ProjectionMessage.user(it.markdown)
                } else {
                    ProjectionMessage.assistant(it.markdown)
                }
            } + ProjectionMessage.user(text)

        val request = CompletionRequest(
            model = modelId,
            messages = projection,
            chat_id = existingChatId,
            id = assistantMessageId,
            parent_id = previousAssistantId,
            user_message = ProjectionMessage.user(text),
            background_tasks = if (isNewChat) {
                BackgroundTasks(title_generation = true, tags_generation = true)
            } else {
                BackgroundTasks()
            },
        )
        activeModelId = modelId
        projectionSnapshot = projection

        currentCall = api.streamingCompletion(CompletionRequests.encode(request))
        drain(currentCall!!, assistantMessageId)
        // The chat id may have been learned mid-stream (chat:list); resolve at finalize.
        finalizeAssistant(assistantMessageId)
    }

    private suspend fun drain(call: Call, assistantMessageId: String) = withContext(Dispatchers.IO) {
        val parser = OwuiStreamParser(json)
        call.execute().use { response ->
            val body = response.body ?: throw OwuiApiException(response.code, null, "Empty body")
            when (classifyTransport(response.header(CONTENT_TYPE))) {
                TransportKind.HttpStream -> sourceFrames(body).forEach { frame ->
                    parser.onFrame(frame).forEach { event -> fold(event, assistantMessageId) }
                }
                TransportKind.JsonCompletion -> {
                    // Plain-JSON completions (tiny models, some pipelines — §6.1): the
                    // whole payload flows through the same fold (choices[].message).
                    val text = body.string()
                    parser.onFrame(SseFrameScanner.Frame(null, text))
                        .forEach { event -> fold(event, assistantMessageId) }
                }
                TransportKind.TaskSocket -> throw OwuiApiException(
                    response.code,
                    null,
                    "Server routed the completion to a socket task; task transports land in M4",
                )
            }
        }
    }

    private fun sourceFrames(body: okhttp3.ResponseBody): Sequence<SseFrameScanner.Frame> = sequence {
        val source = body.source()
        val chunk = ByteArray(CHUNK_BUFFER)
        while (!source.exhausted()) {
            val read = source.inputStream().read(chunk)
            if (read <= 0) break
            yieldAll(scanner.feed(chunk.copyOf(read)))
        }
        yieldAll(scanner.flush())
    }

    private suspend fun fold(event: StreamEvent, assistantMessageId: String) {
        val chatId = _state.value.chatId
        when (event) {
            is StreamEvent.Content -> {
                assistantContent.append(event.text)
                foldPartsFor(assistantMessageId) { it.copy(markdown = assistantContent.toString()) }
                maybeWriteThrough(chatId, assistantMessageId)
            }
            is StreamEvent.Reasoning -> {
                assistantReasoning.append(event.text)
                foldPartsFor(assistantMessageId) { it.copy(reasoning = assistantReasoning.toString()) }
            }
            is StreamEvent.Status -> patch { copy(statusText = if (event.done == true) null else event.text) }
            is StreamEvent.OutputSnapshot -> {
                outItems = event.items
                if (!outputAdopted) {
                    val displayed = displayTextFromOutput(event.items)
                    if (displayed != null && assistantContent.isBlank()) {
                        outputAdopted = true
                    }
                }
                // §17: the presence ALONE makes outputs authoritative (even empty[]);
                // parts derive from outputs from here on.
                outputSeen = true
                foldPartsFor(assistantMessageId) { it.copy(markdown = displayTextFromOutput(event.items) ?: it.markdown) }
            }
            is StreamEvent.FollowUps ->
                foldPartsFor(assistantMessageId) { it.copy(followUps = event.items) }
            is StreamEvent.SourceEmitted ->
                foldPartsFor(assistantMessageId) { it.copy(sourceCount = it.sourceCount + 1) }
            is StreamEvent.TitleUpdate -> chatId?.let { store.setChatTitle(it, event.title) }
            is StreamEvent.TagsUpdate -> chatId?.let { store.setChatTags(it, event.tags) }
            is StreamEvent.ChatListDiscovered -> adoptLearnedChat(event.chatId, assistantMessageId)
            is StreamEvent.Embeds -> {
                embedsHtml = event.htmlList
                foldPartsFor(assistantMessageId) { it }
            }
            is StreamEvent.UsageUpdate -> patch { copy(usage = event.usage) }
            is StreamEvent.ModelSnapshot, is StreamEvent.Deferred -> Unit
            is StreamEvent.ContentSnapshot -> assistantContent = StringBuilder(event.content.orEmpty())
            is StreamEvent.ErrorSurface -> patch { copy(error = event.message) }
            is StreamEvent.FinishReason, is StreamEvent.StreamDone -> Unit
        }
    }

    /** Chat id revealed by the server: persist the optimistic pair + chat row. */
    private suspend fun adoptLearnedChat(chatId: String, assistantMessageId: String) {
        if (_state.value.chatId != null) return // already known (multi-frame safety)
        _state.value = _state.value.copy(chatId = chatId)

        val pending = _state.value.messages.takeLast(2) // optimistic user + assistant pair
        val userMsg = pending.firstOrNull { it.role == MessageEntity.ROLE_USER }
        store.insertNode(
            MessageEntity(
                messageId = userMsg?.id ?: UUID.randomUUID().toString(),
                chatId = chatId,
                parentId = null,
                role = MessageEntity.ROLE_USER,
                content = userMsg?.markdown.orEmpty(),
                timestamp = System.currentTimeMillis(),
            ),
        )
        store.insertNode(
            MessageEntity(
                messageId = assistantMessageId,
                chatId = chatId,
                parentId = userMsg?.id,
                role = MessageEntity.ROLE_ASSISTANT,
                content = assistantContent.toString(),
                outputJson = null,
                done = false,
                timestamp = System.currentTimeMillis(),
            ),
        )
        store.setCurrentMessage(chatId, assistantMessageId)
    }

    private suspend fun maybeWriteThrough(chatId: String?, assistantMessageId: String) {
        val now = System.currentTimeMillis()
        if (chatId == null || now - lastRoomWrite < ROOM_WRITE_INTERVAL_MS) return
        lastRoomWrite = now
        store.updateAssistantContent(
            chatId = chatId,
            messageId = assistantMessageId,
            content = assistantContent.toString(),
            outputJson = null,
            done = false,
            timestamp = now,
        )
    }

    private suspend fun finalizeAssistant(assistantMessageId: String) {
        val now = System.currentTimeMillis()
        val chatId = _state.value.chatId
        if (chatId != null) {
            store.updateAssistantContent(
                chatId = chatId,
                messageId = assistantMessageId,
                content = assistantContent.toString(),
                outputJson = null,
                done = true,
                timestamp = now,
            )
        }
        _state.value = _state.value.copy(busy = false, streamingMessageId = null, statusText = null)

        // §6.3: outlet filters run server-side. Adopt ONLY id-bearing messages.
        if (chatId != null) {
            runCatching {
                val completed = api.chatCompleted(completedBody(chatId, assistantMessageId))
                completedAdopt(completed)
                if (store.refreshServerChat(chatId)) {
                    _state.value = ChatUiModel(
                        chatId = chatId,
                        messages = store.activePath(chatId).map(::renderOf),
                        usage = _state.value.usage,
                    )
                }
            }
        }
    }

    /**
     * Outlet filter merge (§6.3): the completed response echoes the request dict;
     * adopt only objects that carry an id (tree-safe), ignore the projection.
     */
    private suspend fun completedAdopt(response: JsonObject) {
        val messages = response["messages"]
        if (messages is JsonArray) {
            messages.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val id = (obj["id"] as? JsonPrimitive)?.content ?: return@forEach
                val role = (obj["role"] as? JsonPrimitive)?.content ?: return@forEach
                if (role != MessageEntity.ROLE_ASSISTANT && role != MessageEntity.ROLE_USER) return@forEach
                val chatId = _state.value.chatId ?: return@forEach
                store.insertNode(
                    MessageEntity(
                        messageId = id,
                        chatId = chatId,
                        parentId = (obj["parentId"] as? JsonPrimitive)?.content
                            ?: (obj["parent_id"] as? JsonPrimitive)?.content,
                        role = role,
                        content = (obj["content"] as? JsonPrimitive)?.content.orEmpty(),
                        outputJson = obj["output"]?.toString(),
                        done = true,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun completedBody(chatId: String, assistantMessageId: String): String {
        // Same dict shape as the completion request (verified: utils/chat.py:319+
        // chat_completed requires id + model; the outlet filter runs over messages).
        val body: JsonElement = buildJsonObject {
            put("model", activeModelId.orEmpty())
            put("id", assistantMessageId)
            put("chat_id", chatId)
            put("stream", false)
            put(
                "messages",
                JsonArray(
                    projectionSnapshot.map { message ->
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        }
                    },
                ),
            )
        }
        return json.encodeToString(JsonElement.serializer(), body)
    }

    // ── local helpers ──────────────────────────────────────────────────────

    private fun displayTextFromOutput(items: List<JsonElement>): String? {
        val text = items.lastOrNull { item ->
            (item as? JsonObject)?.get("type")?.let { it is JsonPrimitive && it.content == "message" } == true
        }?.textContentOfOutput()
        return text?.takeIf { it.isNotEmpty() }
    }

    private fun JsonElement?.textContentOfOutput(): String? {
        val parts = (this as? JsonArray)?.toList() ?: return null
        return parts.joinToString("") { part ->
            if ((part as? JsonObject)?.get("type")?.let { it is JsonPrimitive && it.content == "output_text" } == true) {
                (part["text"] as? JsonPrimitive)?.content.orEmpty()
            } else {
                ""
            }
        }
    }

    private fun patch(reducer: ChatUiModel.() -> ChatUiModel) {
        _state.value = _state.value.reducer()
    }

    private fun patchAssistant(id: String, transform: (RenderMessage) -> RenderMessage) =
        patch { copy(messages = messages.map { if (it.id == id) transform(it) else it }) }

    private fun describe(e: Exception): String = when (e) {
        is OwuiApiException -> when (e.code) {
            0 -> e.message ?: "Not connected"
            401, 403 -> "The server rejected the request (${e.code})."
            else -> "Server error ${e.code}: ${e.bodyExcerpt?.take(160) ?: "no details"}"
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val CHUNK_BUFFER = 8192
        private const val ROOM_WRITE_INTERVAL_MS = 750L
    }
}

/** Extracts reasoning text from a stored message's output JSON (server fold shape). */
fun MessageEntity.reasoningText(json: Json): String? {
    val output = outputJson ?: return null
    val items = runCatching { json.parseToJsonElement(output) }.getOrNull() as? JsonArray ?: return null
    val reasoningItems = items.filter { item ->
        (item as? JsonObject)?.get("type")?.let { it is JsonPrimitive && it.content == "reasoning" } == true
    }
    if (reasoningItems.isEmpty()) return null
    return reasoningItems.joinToString("") { item ->
        val parts = (item as? JsonObject)?.get("content") as? JsonArray ?: JsonArray(emptyList())
        parts.joinToString("") { part ->
            if ((part as? JsonObject)?.get("type")?.let { it is JsonPrimitive && it.content == "output_text" } == true) {
                (part["text"] as? JsonPrimitive)?.content.orEmpty()
            } else {
                ""
            }
        }
    }.takeIf { it.isNotEmpty() }
}