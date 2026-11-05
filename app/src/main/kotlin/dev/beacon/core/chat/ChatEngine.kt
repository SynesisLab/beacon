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
import timber.log.Timber
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
    /** null in JVM tests — the lease is a device-only concern. */
    private val appContext: android.content.Context?,
    /** null in JVM tests — the taskSocket/socket-events path (M4-d3). */
    private val socket: dev.beacon.core.network.socket.ChatSocket? = null,
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
        /** True while reasoning deltas are the last thing seen (drives the spinner). */
        val reasoningActive: Boolean = false,
        /** §4 branch stepper (OWUI parity): this turn's sibling count + the
         *  active child's index (user turns hold the childrenIds). 1/N hidden
         *  when count ≤ 1. */
        val branchCount: Int = 1,
        val branchIndex: Int = 0,
        /** Render-part list (§4 contract) — the UI renders ONLY through this. */
        val parts: List<ChatPart> = emptyList(),
    )

    data class ChatUiModel(
        val chatId: String? = null,
        val title: String? = null,
        val messages: List<RenderMessage> = emptyList(),
        val streamingMessageId: String? = null,
        val statusText: String? = null,
        val error: String? = null,
        val busy: Boolean = false,
        val usage: JsonElement? = null,
        /** A live ask_user interaction (builtin.py:543), awaiting the user. */
        val inputPrompt: InputRequest? = null,
        /** The context meter from GET /{id} (context_usage; loads fresh only). */
        val contextUsage: dev.beacon.core.api.dto.ContextUsageDto? = null,
    )

    /**
     * `request:user_input` (socket/main.py:1793; the ask_user builtin
     * tools/builtin.py:543-660): 1-3 questions, each with 2-3 options + an
     * optional free-form answer; the ack resolves as {answers: {id: label}} or
     * {status:'cancelled'} and the model continues with the tool's result.
     */
    data class InputQuestion(
        val id: String,
        val header: String,
        val question: String,
        val options: List<InputOption>,
        val allowOther: Boolean,
    )

    data class InputOption(val label: String, val description: String)

    data class InputRequest(
        val questions: List<InputQuestion>,
        val allowOther: Boolean,
        val timeoutMs: Long,
        /** Non-null when delivered as a live socket CALL (ack answer path). */
        val envelope: dev.beacon.core.network.socket.SocketEnvelope? = null,
        /** The pending ask_user to resolve (the tool-call output path). */
        val chatId: String? = null,
        val messageId: String? = null,
        val callId: String? = null,
    )

    /**
     * A non-ask_user tool call the server holds PENDING for user approval
     * (tool_approval.py:23-31; pause_for_tool_approval middleware.py:3347 —
     * only when the chat params run tool_approval_mode 'ask', middleware
     * :3136/:5740). Approve = {action:'approve'} → the tools run (resume
     * 'tools', tool_approval.py:119-125,166-167); Reject = {action:'reject'}
     * → a "rejected by user" function_call_output rides in and the MODEL
     * resumes (:126-136). The resolve response is the resumed completion —
     * the caller drains it like a normal send (same path as answerInput).
     */
    data class ToolApprovalRequest(
        val chatId: String,
        val messageId: String,
        val callId: String,
        val name: String,
        val arguments: String,
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
    private var reasoningActive = false
    private var outputAdopted = false
    private var outputSeen = false
    private var outItems: List<JsonElement> = emptyList()
    private var embedsHtml: List<String> = emptyList()
    private var lastRoomWrite = 0L
    private var lastSyncAt = 0L
    private var activeModelId: String? = null
    private var projectionSnapshot: List<ProjectionMessage> = emptyList()
    private var joinedChatId: String? = null

    /** Joins room chat:{id} (the SHARED throttled output = live streaming);
     *  leaves the previous room first. Temp chats never join (they share
     *  nothing server-side; update_db/room logic at socket/main.py:1628). */
    private fun joinChatRoom(chatId: String?) {
        val id = chatId ?: return
        if (id == joinedChatId) return
        joinedChatId?.let { socket?.leaveChat(it) }
        joinedChatId = if (socket?.joinChat(id) == true) id else joinedChatId
        Timber.d("chat room %s -> %s", id, if (joinedChatId == id) "joined" else "join failed")
    }

    /** Entry: adopt the server chat tree and paint Room-first (§6.4). */
    suspend fun load(existingChatId: String?) {
        // THE SOCKET EVENT COLLECTOR (M4-d3): on 0.12 servers the envelope
        // events (chat:list, chat:title/tags, status, follow_ups, task-completion
        // streams) ride the SOCKET ONLY (emit_chat_list_event →
        // get_event_emitter → room user:{id}, main.py:296-307+1626-1673) — the
        // HTTP SSE is the raw upstream passthrough. Bind ONE collector for the
        // engine's chat; rebind per load/send.
        socket?.let { bindEventCollector() }
        if (existingChatId == null) {
            _state.value = ChatUiModel()
            return
        }
        // The chat-room presence = the live-output stream (§streaming).
        joinChatRoom(existingChatId)
        // Paint ROOM FIRST so opening a chat switches instantly (user report:
        // the tap showed the previous surface until the network answered).
        runCatching {
            val rows = store.activePath(existingChatId)
            if (rows.isNotEmpty()) {
                paintFromStore(existingChatId)
            }
        }
        try {
            val payload = store.fetchServerTree(existingChatId)
            if (payload != null) {
                store.upsertServerChat(payload)
                paintFromStore(existingChatId)
                _state.value = _state.value.copy(title = payload.title, contextUsage = payload.context_usage)
            } else if (_state.value.messages.isEmpty()) {
                _state.value = ChatUiModel(chatId = existingChatId, error = "Instance unreachable")
            }
        } catch (e: OwuiApiException) {
            if (_state.value.messages.isEmpty()) {
                _state.value = ChatUiModel(chatId = existingChatId, error = describe(e))
            }
        }
    }

    suspend fun paintFromStore(chatId: String) {
        val path = store.activePath(chatId)
        val byId = store.messages(chatId).associateBy { it.messageId }
        // The topbar title rides the ROOM ROW (the chat:title event writes
        // there) — the fresh-VM paint has no engine title yet, and a failed
        // adopt no longer strands the topbar at "New chat" (2026-11-04).
        val roomTitle = runCatching { store.chatTitle(chatId) }.getOrNull()
        _state.value = ChatUiModel(
            chatId = chatId,
            title = roomTitle ?: _state.value.title,
            messages = path.map { row ->
                // §4 version count: a node's siblings = its PARENT's childrenIds
                // (the assistant versions under the user turn); the index = this
                // node's position among them.
                val parent = row.parentId?.let { byId[it] }
                renderOf(row).copy(
                    branchCount = (parent?.childrenIds?.size ?: 1).coerceAtLeast(1),
                    branchIndex = parent?.childrenIds?.indexOf(row.messageId)?.coerceAtLeast(0) ?: 0,
                )
            },
            usage = _state.value.usage,
            contextUsage = _state.value.contextUsage,
        )
    }

    /** §4 the action-row stepper's ◀/▶: [assistantId] = the viewed version; its
     *  parent holds the siblings. Picks the requested sibling, walks to the
     *  branch's leaf, repaints + saves the tree (the visible-path pointer moves;
     *  nothing is deleted). */
    fun setBranch(assistantId: String, index: Int) {
        val chatId = _state.value.chatId ?: return
        scope.launch {
            val rows = store.messages(chatId)
            val pressed = rows.firstOrNull { it.messageId == assistantId } ?: return@launch
            val holder = pressed.parentId ?: return@launch
            val child = (rows.firstOrNull { it.messageId == holder } ?: return@launch)
                .childrenIds
                .getOrNull(index) ?: return@launch
            var leaf = child
            while (true) {
                val kids = rows.filter { it.parentId == leaf }
                if (kids.isEmpty()) break
                leaf = kids.maxBy { it.timestamp ?: 0L }.messageId
            }
            store.setCurrentMessage(chatId, leaf)
            store.saveServerTree(chatId, leaf, activeModelId)
            paintFromStore(chatId)
        }
    }

    private fun renderOf(entity: MessageEntity): RenderMessage {
        val outElement = entity.outputJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val outputItems = (outElement as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()
        val reasoning = entity.reasoningText(json)
        val base = RenderMessage(
            id = entity.messageId,
            role = entity.role,
            markdown = entity.content.orEmpty(),
            reasoning = reasoning,
            pending = !entity.done,
        )
        return base.copy(
            parts = PartBuilder.build(
                FoldedMessage(
                    contentMd = entity.content.orEmpty(),
                    reasoningMd = reasoning,
                    // An unfinished message whose content never arrived may have
                    // died mid-reasoning — keep the card spinning there only.
                    reasoningActive = !entity.done && entity.content.isNullOrBlank(),
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
                            reasoningActive = reasoningActive,
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
    fun send(
        text: String,
        modelId: String,
        files: List<FileAttachment> = emptyList(),
        features: Features? = null,
        temporary: Boolean = false,
        toolIds: List<String> = emptyList(),
        skillIds: List<String> = emptyList(),
    ) {
        if (text.isBlank() || _state.value.busy) return
        launchStream {
            sendParams(text, modelId, toolIds = toolIds, skillIds = skillIds)
                .copy(files = files, features = features, temporary = temporary)
        }
    }

    /** §4: regenerate = a sibling branch under the same parent (new assistant id).
     *  Selections carry over (the web's preserveSelections). */
    fun regenerate(modelId: String, toolIds: List<String> = emptyList(), skillIds: List<String> = emptyList()) {
        if (_state.value.busy) return
        launchStream { sendParams("", modelId, regenerate = true, toolIds = toolIds, skillIds = skillIds) }
    }

    /**
     * §4 VERSION-TREE regenerate (user request 2026-11-03): the pressed
     * assistant's ↻ makes a SIBLING under ITS OWN user turn; the visible path
     * truncates there. The later turns keep living under the old branch
     * (never deleted) — the new sibling = the active leaf.
     */
    fun regenerateAt(messageId: String, modelId: String, toolIds: List<String> = emptyList(), skillIds: List<String> = emptyList()) {
        if (_state.value.busy) return
        launchStream {
            sendParams(
                "", modelId,
                regenerate = true,
                toolIds = toolIds,
                skillIds = skillIds,
                regenerateAtMessageId = messageId,
            )
        }
    }

    /** §4/§9.3: edit-and-retry — a sibling branch seeded by the same user turn. */
    fun editAndRetry(
        messageId: String,
        newContent: String,
        modelId: String,
        toolIds: List<String> = emptyList(),
        skillIds: List<String> = emptyList(),
    ) {
        if (newContent.isBlank() || _state.value.busy) return
        launchStream {
            sendParams(newContent, modelId, regenerate = false, replaceUserMessageId = messageId, toolIds = toolIds, skillIds = skillIds)
        }
    }

    /** §9.3: continue an interrupted/ended assistant turn (assistant_message_id). */
    fun continueResponse(modelId: String) {
        if (_state.value.busy) return
        launchStream { continueParams(modelId) }
    }

    fun rate(messageId: String, rating: Int) {
        val chatId = _state.value.chatId ?: return
        val model = activeModelId
        scope.launch {
            val ok = runCatching { api.rate(chatId, model, messageId, rating) }.getOrDefault(false)
            if (ok) {
                patch { copy(messages = messages) } // rating state lands via meta in M4
            }
        }
    }

    private fun launchStream(block: suspend () -> SendPlan) {
        if (_state.value.busy) return
        // A new send abandons any open interactive leg (the server times the
        // held calls out on its own).
        _inputPrompt.value = null
        _toolApproval.value = null
        streamJob = scope.launch {
            // The foreground lease holds the stream alive while backgrounded (§6.3).
            appContext?.let { StreamingLeaseService.start(it) }
            try {
                sendFromParams(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = describe(e),
                    busy = false,
                    streamingMessageId = null,
                )
            } finally {
                appContext?.let { StreamingLeaseService.stop(it) }
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

    /**
     * §9.1/§9.2: silent reconciler sync (flick-to-refetch, resume). Debounced 1s;
     * disabled mid-stream (the stream is the source of truth while live).
     * Returns true when the server had changes — the UI shows the Updated pill.
     */
    suspend fun sync(): Boolean {
        val chatId = _state.value.chatId ?: return false
        if (_state.value.busy) return false
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < SYNC_DEBOUNCE_MS) return false
        lastSyncAt = now

        val payload = store.fetchServerTree(chatId) ?: return false
        val serverNodes = payload.chat?.history?.messages?.values.orEmpty().toList()
        val serverCurrent = payload.chat?.history?.currentId
        val diffs = ChatReconciler.reconcile(
            local = store.messages(chatId),
            server = serverNodes,
            chatId = chatId,
            serverCurrentId = serverCurrent,
            localCurrentId = null,
        )
        diffs.upserts.forEach { store.insertNode(it) }
        diffs.pointer?.let { store.setCurrentMessage(chatId, it) }
        paintFromStore(chatId)
        return diffs.upserts.isNotEmpty() || diffs.pointer != null
    }

    // ── send pipeline ──────────────────────────────────────────────────────

    private data class SendPlan(
        val text: String,
        val modelId: String,
        val files: List<dev.beacon.core.chat.FileAttachment> = emptyList(),
        val regenerate: Boolean = false,
        /** edit-and-retry: the sibling branch replaces this user turn's content. */
        val replaceUserMessageId: String? = null,
        /** continue: append to this assistant turn (same id on the wire). */
        val continueAssistantId: String? = null,
        /** composer feature toggles (§9.3; server-gated) — the wire-typed set. */
        val features: Features? = null,
        /** The model's default workspace TOOLS + SKILLS (Chat.svelte:1121-1140:
         *  meta.toolIds ∪ sticky, meta.skillIds ∩ active) — the wire's
         *  tool_ids/skill_ids; empty = no tools selected. */
        val toolIds: List<String> = emptyList(),
        val skillIds: List<String> = emptyList(),
        /** Version-tree regenerate: the pressed assistant's id — its ↻ forks
         *  a sibling under its own user turn and truncates the visible path. */
        val regenerateAtMessageId: String? = null,
        /** temporary chat: client-owned `temporary:<uuid>` id, never saved (§9.3) */
        val temporary: Boolean = false,
    )

    private suspend fun sendParams(
        text: String,
        modelId: String,
        regenerate: Boolean = false,
        replaceUserMessageId: String? = null,
        features: Features? = null,
        temporary: Boolean = false,
        toolIds: List<String> = emptyList(),
        skillIds: List<String> = emptyList(),
        regenerateAtMessageId: String? = null,
    ): SendPlan = SendPlan(text, modelId, emptyList(), regenerate, replaceUserMessageId, features = features, temporary = temporary, toolIds = toolIds, skillIds = skillIds, regenerateAtMessageId = regenerateAtMessageId)

    /** Temporary chats own a client-generated `temporary:<uuid>` id (utils/chat_id.py verified). */
    private var tempSessionId: String? = null

    private suspend fun continueParams(modelId: String): SendPlan = SendPlan(
        text = "",
        modelId = modelId,
        continueAssistantId = _state.value.messages.lastOrNull { it.role == MessageEntity.ROLE_ASSISTANT }?.id
            ?: _state.value.streamingMessageId,
    )

    private suspend fun sendFromParams(plan: SendPlan) {
        // Temporary chats: the id scheme is client-owned, nothing persists (§9.3).
        val tempId = if (plan.temporary) {
            (tempSessionId ?: "temporary:${UUID.randomUUID()}").also { tempSessionId = it }
        } else {
            null
        }
        if (tempId != null) {
            _state.value = _state.value.copy(chatId = tempId)
        }
        // v0.12 protocol (the fresh-send 409/temp-chat bug, 2026-11-03): a
        // chat-less send is a TEMP chat server-side (chat_id='' → saveless).
        // The SAVED first turn = the CLIENT creates the chat first — the web's
        // initChatHandler → POST /api/v1/chats/new (Chat.svelte:4817-4847) —
        // and the completion runs with the real chat_id (+ task mode).
        if (tempId == null && _state.value.chatId == null) {
            store.createServerChat("New Chat", plan.modelId)?.let { created ->
                if (_state.value.chatId == null) {
                    _state.value = _state.value.copy(chatId = created.id, title = created.title)
                }
            }
        }
        val existingChatId = _state.value.chatId
        val isNewChat = existingChatId == null
        // Join the ROOM the moment the id is real: the shared output throttle
        // is the live stream (§streaming).
        joinChatRoom(existingChatId)
        val now = System.currentTimeMillis()
        val previousAssistantId = _state.value.messages.lastOrNull { it.role == MessageEntity.ROLE_ASSISTANT }?.id

        // ── version-tree regenerate (§4; user request 2026-11-03) ──
        // The pressed assistant's ↻ = a SIBLING branch under ITS OWN user
        // turn; the visible path truncates there (later turns stay under the
        // old branch, never deleted). The replaced message goes back on the
        // wire as regeneration_message_id.
        val visible = _state.value.messages
        val pressedAssistantId: String? = when {
            plan.regenerateAtMessageId != null ->
                visible.firstOrNull { it.id == plan.regenerateAtMessageId }?.id
            plan.regenerate -> previousAssistantId
            else -> null
        }
        val pressedIndex = pressedAssistantId?.let { at -> visible.indexOfFirst { it.id == at } } ?: -1
        val pressedUserTurn = if (pressedIndex > 0) {
            visible.getOrNull(pressedIndex - 1)?.takeIf { it.role == MessageEntity.ROLE_USER }
        } else {
            null
        }
        // The truncated list the turn hangs from (pressed regen: everything
        // from the pressed assistant ON = parked under the old branch).
        val visiblePath = when {
            plan.regenerate && pressedUserTurn != null ->
                visible.takeWhile { it.id != pressedUserTurn.id } + pressedUserTurn
            else -> visible
        }

        // The user turn the response hangs from (tree semantics, agent.md §6.4).
        // A sibling branch attaches to the user turn BEFORE the replaced assistant.
        val lastAssistantIndex = visiblePath.indexOfLast { it.role == MessageEntity.ROLE_ASSISTANT }
        val userTurnId: String? = when {
            plan.regenerate -> visiblePath.lastOrNull { it.role == MessageEntity.ROLE_USER }?.id
                ?: plan.replaceUserMessageId
            plan.continueAssistantId != null -> previousAssistantId
            else -> null // fresh turn
        }
        val userTurnIndexForPaint = userTurnId?.let { at -> visiblePath.indexOfFirst { it.id == at } } ?: -1
        val parentIdForWire: String? = when {
            plan.regenerate -> userTurnId // parent_id = the user message (wire semantics: the assistant's parent)
            plan.continueAssistantId != null -> previousAssistantId
            else -> previousAssistantId
        }
        val assistantMessageId = plan.continueAssistantId ?: UUID.randomUUID().toString()

        // Optimistic UI state; Room rows follow (fresh chats persist post chat:list).
        val userMessageId = plan.replaceUserMessageId ?: UUID.randomUUID().toString()
        _state.value = _state.value.copy(
            busy = true,
            error = null,
            streamingMessageId = assistantMessageId,
            messages = buildList {
                when {
                    plan.regenerate -> {
                        // The truncated version + the pending sibling IN PLACE.
                        if (userTurnIndexForPaint >= 0) {
                            addAll(visiblePath.take(userTurnIndexForPaint + 1))
                        } else {
                            addAll(visiblePath)
                        }
                        add(RenderMessage(assistantMessageId, MessageEntity.ROLE_ASSISTANT, pending = true))
                    }
                    plan.continueAssistantId != null -> {
                        addAll(visible)
                        add(RenderMessage(assistantMessageId, MessageEntity.ROLE_ASSISTANT, pending = true))
                    }
                    else -> {
                        addAll(visible)
                        add(
                            RenderMessage(
                                userMessageId,
                                MessageEntity.ROLE_USER,
                                markdown = plan.text,
                            ),
                        )
                        add(RenderMessage(assistantMessageId, MessageEntity.ROLE_ASSISTANT, pending = true))
                    }
                }
            },
        )

        if (!isNewChat && plan.continueAssistantId == null) {
            if (plan.regenerate) {
                store.insertNode(
                    MessageEntity(
                        messageId = assistantMessageId,
                        chatId = existingChatId!!,
                        parentId = userTurnId,
                        role = MessageEntity.ROLE_ASSISTANT,
                        content = "",
                        done = false,
                        timestamp = now,
                    ),
                )
            }
            store.setCurrentMessage(existingChatId, assistantMessageId)
            // §4 version-tree: the sibling fork must reach the SERVER (the web
            // saves the tree per regenerate; models/chats.py:1134 merges the
            // streamed content into the EXISTING node) — device log
            // 2026-11-03: without this the fork orphaned itself
            // (parentId=null) and the user turn fell off the active path.
            if (plan.regenerate && existingChatId != null) {
                store.saveServerTree(existingChatId, assistantMessageId, plan.modelId)
                pendingTreeSave = true
            }
        }

        assistantContent = StringBuilder()
        assistantReasoning = StringBuilder()
        reasoningActive = false
        outputAdopted = false
        outputSeen = false
        outItems = emptyList()
        embedsHtml = emptyList()
        lastRoomWrite = now
        scanner = SseFrameScanner()

        val projection = projectionFor(plan) + buildList {
            if (plan.regenerate.not() && plan.continueAssistantId == null) {
                add(ProjectionMessage.user(plan.text))
            }
        }

        val request = CompletionRequest(
            model = plan.modelId,
            messages = projection,
            chat_id = existingChatId,
            id = assistantMessageId,
            parent_id = parentIdForWire,
            user_message = when {
                plan.continueAssistantId != null -> null
                plan.regenerate -> null
                // The web sends the FULL client-side user message (Chat.svelte:3375-3385):
                // {id, parentId, role, content, timestamp} — `insert_chat_turn`
                // 409s without the id (chats.py:1321-1331).
                else -> buildJsonObject {
                    put("id", userMessageId)
                    parentIdForWire?.let { put("parentId", it) }
                    put("role", "user")
                    put("content", plan.text)
                    put("timestamp", now / 1000)
                }
            },
            files = plan.files,
            regeneration_message_id = if (plan.regenerate) (pressedAssistantId ?: previousAssistantId) else null,
            regeneration_prompt = null,
            assistant_message_id = plan.continueAssistantId,
            features = plan.features ?: Features(),
            tool_ids = plan.toolIds,
            skill_ids = plan.skillIds,
            background_tasks = when {
                plan.temporary -> BackgroundTasks() // nothing persists for temp chats
                isNewChat -> BackgroundTasks(title_generation = true, tags_generation = true, follow_up_generation = true)
                else -> BackgroundTasks(follow_up_generation = true)
            },
        )
        activeModelId = plan.modelId
        projectionSnapshot = projection

        // M4-d3: with a live socket, the web-shaped `session_id` swaps the
        // transport to a background task — HTTP {status, task_ids, chat_id}
        // (main.py:1916-1930), stream over socket `events` (§6.1 taskSocket).
        // Not connected within 3s → plain SSE, byte-identical to the M1 path.
        val socketSessionId = socket?.takeIf { it.ensureConnected(3_000) }?.sessionId()
        Timber.d("send: chat=%s assistant=%s socket=%s", existingChatId, assistantMessageId, socketSessionId)
        // The event collector binds PERMANENTLY (rebound per send): the fresh
        // chat's `chat:list` arrives on the socket while the HTTP SSE carries
        // the deltas — a per-send subscribe would miss the early events.
        taskTerminated = false
        owuiParserForTask.reset()
        activeAssistantId = assistantMessageId
        bindEventCollector()
        currentCall = api.streamingCompletion(
            CompletionRequests.encode(request.copy(session_id = socketSessionId)),
        )
        var taskPayload: JsonObject? = null
        drain(currentCall!!, assistantMessageId) { taskPayload = it }
        if (taskPayload != null) {
            drainTaskSocket(taskPayload!!, assistantMessageId)
        }
        // The chat id may have been learned mid-stream (chat:list); resolve at finalize.
        finalizeAssistant(assistantMessageId)
    }

    /** Set by the terminal fold (chat:completion done:true / [DONE]). */
    @Volatile
    private var taskTerminated = true

    /** A version-tree fork happened this stream → the finalize re-saves the tree. */
    private var pendingTreeSave = false

    /** The assistant message the event collector folds into (per send). */
    private var activeAssistantId: String? = null

    /** The collector's fold parser (own Responses state; reset per bind/send). */
    private val owuiParserForTask = OwuiStreamParser(json)

    /**
     * Collects socket `events` for THIS engine's chat, folding each envelope's
     * `data` through the SAME parser (the socket event family IS the SSE event
     * family — middleware.py:6372 vs :6476). Bound at load()/send(); unknown
     * chat ids pass (fresh sends learn their id mid-stream via chat:list).
     */
    private fun bindEventCollector() {
        val s = socket ?: return
        eventCollector?.cancel()
        eventCollector = scope.launch {
            s.events.collect { envelope ->
                // Interactive host calls (ask_user) surface on the UI, not the fold.
                val type = (envelope.data["type"] as? JsonPrimitive)?.content
                if (type == "request:user_input" && envelope.canReply()) {
                    _inputPrompt.value = parseInputRequest(envelope)
                    return@collect
                }
                val chatId = _state.value.chatId
                val sameChat = envelope.chatId == null || chatId == null || envelope.chatId == chatId
                if (sameChat) {
                    val target = activeAssistantId ?: envelope.messageId ?: ""
                    owuiParserForTask.onFrame(SseFrameScanner.Frame(null, envelope.data.toString()))
                        .forEach { fold(it, target) }
                }
            }
        }
    }

    private var eventCollector: Job? = null

    /** Open ask_user interaction, rendered by the chat screen's dialog. */
    private val _inputPrompt = MutableStateFlow<InputRequest?>(null)
    val inputPrompt: StateFlow<InputRequest?> = _inputPrompt.asStateFlow()

    /** Open tool-call approval (tool_approval.py), rendered by ToolApprovalDialog. */
    private val _toolApproval = MutableStateFlow<ToolApprovalRequest?>(null)
    val toolApproval: StateFlow<ToolApprovalRequest?> = _toolApproval.asStateFlow()

    /** builtin.py:620-640 — the normalized question shape arriving on the wire. */
    private fun parseInputRequest(envelope: dev.beacon.core.network.socket.SocketEnvelope): InputRequest {
        val questions = (envelope.data["questions"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val q = raw as? JsonObject ?: return@mapNotNull null
            val id = (q["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            InputQuestion(
                id = id,
                header = (q["header"] as? JsonPrimitive)?.content ?: "Question",
                question = (q["question"] as? JsonPrimitive)?.content ?: "",
                options = (q["options"] as? JsonArray).orEmpty().mapNotNull { optionRaw ->
                    val option = optionRaw as? JsonObject ?: return@mapNotNull null
                    InputOption(
                        (option["label"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                        (option["description"] as? JsonPrimitive)?.content ?: "",
                    )
                },
                allowOther = (q["allow_other"] as? JsonPrimitive)?.jsonBool() ?: false,
            )
        }
        return InputRequest(
            questions = questions,
            allowOther = (envelope.data["allow_other"] as? JsonPrimitive)?.jsonBool() ?: true,
            timeoutMs = 120_000L,
            envelope = envelope,
        )
    }

    /**
     * Interactive tool call in the CURRENT assistant's output: the model
     * called `ask_user` and the turn paused (the server holds the function_call
     * pending, tool_approval.py:23-31). The dialog renders from the call's
     * ARGUMENTS (questions/options/allow_other, tools/builtin.py:543-660) and
     * answers via POST /api/v1/chats/{id}/messages/{message_id}/resolve — the
     * server appends the function_call_output and RE-RUNS chat_completion, so
     * the resumed stream drains through the normal transport paths.
     */
    private fun maybeSurfacePendingInput(output: List<JsonElement>) {
        val messageId = _state.value.streamingMessageId ?: return
        val chatId = _state.value.chatId
        var approval: ToolApprovalRequest? = null
        val openCallIds = mutableSetOf<String>()
        for (item in output) {
            val call = item as? JsonObject ?: continue
            if ((call["type"] as? JsonPrimitive)?.content != "function_call") continue
            val callId = ((call["call_id"] as? JsonPrimitive)?.content
                ?: (call["id"] as? JsonPrimitive)?.content) ?: continue
            val status = (call["status"] as? JsonPrimitive)?.content
            // pending_tool_calls (tool_approval.py:23-31, :110): open = no
            // function_call_output for its call_id, not approved:true, and
            // a non-terminal status.
            val hasOutput = output.any { other ->
                val o = other as? JsonObject
                (o?.get("type") as? JsonPrimitive)?.content == "function_call_output" &&
                    (o["call_id"] as? JsonPrimitive)?.content == callId
            }
            val open = status in listOf("pending", "queued", "requires_approval") &&
                !hasOutput &&
                (call["approved"] as? JsonPrimitive)?.jsonBool() != true
            if (!open) continue
            openCallIds.add(callId)
            if ((call["name"] as? JsonPrimitive)?.content != "ask_user") {
                // A held regular tool = the approve/reject leg (first wins).
                if (approval == null && chatId != null) {
                    approval = ToolApprovalRequest(
                        chatId = chatId,
                        messageId = messageId,
                        callId = callId,
                        name = (call["name"] as? JsonPrimitive)?.content.orEmpty(),
                        arguments = (call["arguments"] as? JsonPrimitive)?.content ?: "",
                    )
                }
                continue
            }
            // The pending interaction needs the RESOLVE leg: the chat id must
            // be real (the resolve route reads the stored message; a temp
            // chat's ask_user can't be answered — tool_approval.py:74).
            if (chatId == null) continue
            val args = (call["arguments"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: continue
            val parsed = runCatching { json.parseToJsonElement(args) }.getOrNull()
            val questions = (parsed as? JsonObject)?.get("questions") as? JsonArray
            if (questions == null) continue
            val questionsParsed = questions.mapNotNull { raw ->
                val q = raw as? JsonObject ?: return@mapNotNull null
                val qId = (q["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                InputQuestion(
                    id = qId,
                    header = (q["header"] as? JsonPrimitive)?.content ?: "Question",
                    question = (q["question"] as? JsonPrimitive)?.content ?: "",
                    options = (q["options"] as? JsonArray).orEmpty().mapNotNull { optionRaw ->
                        val option = optionRaw as? JsonObject ?: return@mapNotNull null
                        InputOption(
                            (option["label"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                            (option["description"] as? JsonPrimitive)?.content ?: "",
                        )
                    },
                    allowOther = (q["allow_other"] as? JsonPrimitive)?.jsonBool() ?: true,
                )
            }
            if (questionsParsed.isEmpty()) continue
            _inputPrompt.value = InputRequest(
                questions = questionsParsed,
                allowOther = ((parsed as JsonObject)["allow_other"] as? JsonPrimitive)?.jsonBool() ?: true,
                timeoutMs = ((parsed)["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 120_000L,
                chatId = chatId,
                messageId = messageId,
                callId = callId,
                envelope = null,
            )
            return
        }
        // Resolution cleanup: a snapshot that no longer carries the OPEN
        // surfaced call (approved:true / rejected / its output arrived) drops
        // the card — the resolve drain's own OutputSnapshot re-clears it.
        _toolApproval.value?.let { prev ->
            if (prev.callId !in openCallIds) _toolApproval.value = null
        }
        if (approval != null) _toolApproval.value = approval
    }

    /** Intent: answer the open ask_user — via the resolve route (HTTP), or the
     *  live socket ack when the server delivered the interactive call. */
    fun answerInput(answers: Map<String, String>) {
        val prompt = _inputPrompt.value ?: return
        _inputPrompt.value = null
        // The live ack (when the server made the interactive call) and the
        // HTTP resolve are the same resolution from the server's view; the
        // HTTP one wins whenever the chat is saved (the resume = a normal
        // completion drain, §6.1).
        prompt.envelope?.reply(
            buildJsonObject {
                put("answers", buildJsonObject {
                    answers.forEach { (id, label) -> put(id, kotlinx.serialization.json.JsonPrimitive(label)) }
                })
            },
        )
        val chatId = prompt.chatId ?: _state.value.chatId ?: return
        val messageId = prompt.messageId ?: _state.value.streamingMessageId ?: return
        resolveAndDrain(chatId, messageId, prompt.callId.orEmpty(), "answer", answers)
    }

    /**
     * Shared tail of ALL resolve legs (answer/approve/reject): the server
     * appends the function_call_output (the action table: approve tools
     * tool_approval.py:119-125, reject :126-136, answer :137-160) and RE-RUNS
     * the completion — this POST returns whatever chat_completion returns (an
     * SSE stream or the {task_ids} JSON), so drain it like a normal send.
     */
    private fun resolveAndDrain(
        chatId: String,
        messageId: String,
        callId: String,
        action: String,
        answers: Map<String, String>?,
    ) {
        scope.launch {
            taskTerminated = false
            activeAssistantId = messageId
            val resolveCall = try {
                api.resolveToolCall(chatId, messageId, callId, action, answers)
            } catch (e: Exception) {
                patch { copy(error = describe(e)) }
                return@launch
            }
            try {
                var payload: JsonObject? = null
                drain(resolveCall, messageId, onTaskPayload = { payload = it })
                if (payload != null) drainTaskSocket(payload, messageId)
                finalizeAssistant(messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                patch { copy(error = describe(e)) }
            }
        }
    }

    /** Intent: approve the open tool call → the tools run (resume 'tools'). */
    fun approveToolCall() {
        val request = _toolApproval.value ?: return
        _toolApproval.value = null
        resolveAndDrain(request.chatId, request.messageId, request.callId, "approve", null)
    }

    /** Intent: reject the open tool call → a rejected function_call_output
     *  rides in and the MODEL resumes (tool_approval.py:126-136,168-170). */
    fun rejectToolCall() {
        val request = _toolApproval.value ?: return
        _toolApproval.value = null
        resolveAndDrain(request.chatId, request.messageId, request.callId, "reject", null)
    }

    /** Intent: cancel the open ask_user ({status:'cancelled'} = a timed-out resolve). */
    fun cancelInput() {
        val prompt = _inputPrompt.value ?: return
        _inputPrompt.value = null
        val chatId = prompt.chatId ?: _state.value.chatId ?: return
        val messageId = prompt.messageId ?: return
        prompt.envelope?.cancelReply()
        scope.launch {
            runCatching {
                api.resolveToolCall(chatId, messageId, prompt.callId.orEmpty(), "answer", timedOut = true).execute().use { }
            }
        }
    }

    /**
     * The task-socket wait leg: the collector is already running; wait for the
     * terminal `chat:completion {done:true}` (middleware.py:6341-6379) or
     * recover by REFETCH after silence (§9.2 poll-recovery: GET /api/v1/chats/{id}).
     */
    private suspend fun drainTaskSocket(payload: JsonObject, assistantMessageId: String) {
        val chatId = (payload["chat_id"] as? JsonPrimitive)?.content
        chatId?.takeIf { _state.value.chatId == null }?.let { adopted ->
            _state.value = _state.value.copy(chatId = adopted)
            joinChatRoom(adopted)
        }
        val terminated = kotlinx.coroutines.withTimeoutOrNull(45_000) {
            while (!taskTerminated) kotlinx.coroutines.delay(50)
        } != null
        if (!terminated) {
            val currentChatId = _state.value.chatId ?: return
            runCatching {
                store.fetchServerTree(currentChatId)?.let { store.upsertServerChat(it) }
                paintFromStore(currentChatId)
            }.onFailure { e ->
                patch { copy(error = describe(e)) }
            }
        } else {
            // Trailing events (title/tags/follow_ups) land right after the
            // terminal; a short non-blocking window catches them.
            kotlinx.coroutines.delay(800)
        }
    }

    /** Visible-history projection of the branch, honoring the plan's edits. */
    private fun projectionFor(plan: SendPlan): List<ProjectionMessage> {
        val all = _state.value.messages
        val bounded = when {
            plan.regenerate -> {
                // Everything up to AND INCLUDING the user turn the response hangs
                // from; the assistant being regenerated (and anything after it)
                // is excluded — the server regenerates from the parent.
                val stopIndex = all.indexOfLast { it.role == MessageEntity.ROLE_USER }
                if (stopIndex >= 0) all.take(stopIndex + 1) else emptyList()
            }
            plan.continueAssistantId != null -> all // full branch including the assistant
            else -> all
        }
        return bounded
            .filterNot { it.id == _state.value.streamingMessageId && it.pending && (plan.regenerate || plan.continueAssistantId == null) }
            .map { message ->
                if (plan.replaceUserMessageId == message.id) {
                    ProjectionMessage.user(plan.text)
                } else if (message.role == MessageEntity.ROLE_USER) {
                    ProjectionMessage.user(message.markdown)
                } else {
                    ProjectionMessage.assistant(message.markdown)
                }
            }
    }

    /** Non-null return = the classified body was a background task ({task_ids}). */
    private suspend fun drain(
        call: Call,
        assistantMessageId: String,
        onTaskPayload: (JsonObject) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val parser = OwuiStreamParser(json)
        call.execute().use { response ->
            val body = response.body ?: throw OwuiApiException(response.code, null, "Empty body")
            val contentType = response.header(CONTENT_TYPE)
            when (classifyTransport(contentType)) {
                TransportKind.HttpStream -> sourceFrames(body).forEach { frame ->
                    parser.onFrame(frame).forEach { event -> fold(event, assistantMessageId) }
                }
                TransportKind.JsonCompletion, TransportKind.TaskSocket -> {
                    // Buffer and classify on CONTENT: `{task_ids,…}` = background
                    // task (main.py:1916, some servers/paths reply JSON regardless
                    // of our intent); plain JSON = §6.1 jsonCompletion.
                    val text = body.string()
                    val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
                    val payload = element as? JsonObject
                    Timber.d("drain: ct=%s body=%s", contentType, text.take(220))
                    if (payload != null && (payload["task_ids"] != null || payload["task_id"] != null)) {
                        parser.reset()
                        onTaskPayload(payload)
                    } else {
                        parser.onFrame(SseFrameScanner.Frame(null, text))
                            .forEach { event -> fold(event, assistantMessageId) }
                    }
                }
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
                reasoningActive = false // reasoning ended the moment content showed
                foldPartsFor(assistantMessageId) { it.copy(markdown = assistantContent.toString(), reasoningActive = false) }
                maybeWriteThrough(chatId, assistantMessageId)
            }
            is StreamEvent.Reasoning -> {
                assistantReasoning.append(event.text)
                reasoningActive = true
                foldPartsFor(assistantMessageId) { it.copy(reasoning = assistantReasoning.toString(), reasoningActive = true) }
            }
            is StreamEvent.Status -> patch { copy(statusText = if (event.done == true) null else event.text) }
            is StreamEvent.OutputSnapshot -> {
                outItems = event.items
                reasoningActive = false // content stage reached (§: spinner off)
                maybeSurfacePendingInput(event.items)
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
            is StreamEvent.TitleUpdate -> chatId?.let { chat ->
                store.setChatTitle(chat, event.title)
                // The topbar reads the ENGINE title; the room write alone left
                // every chat created in-app titled "New chat" (2026-11-04).
                _state.value = _state.value.copy(title = event.title)
            }
            is StreamEvent.TagsUpdate -> chatId?.let { store.setChatTags(it, event.tags) }
            is StreamEvent.ChatListDiscovered -> adoptLearnedChat(event.chatId, assistantMessageId)
            is StreamEvent.Embeds -> {
                embedsHtml = event.htmlList
                foldPartsFor(assistantMessageId) { it }
            }
            is StreamEvent.UsageUpdate -> patch { copy(usage = event.usage) }
            is StreamEvent.ModelSnapshot, is StreamEvent.Deferred -> Unit
            is StreamEvent.ContentSnapshot -> {
                assistantContent = StringBuilder(event.content.orEmpty())
                reasoningActive = false
                foldPartsFor(assistantMessageId) { it.copy(markdown = event.content.orEmpty(), reasoningActive = false) }
            }
            is StreamEvent.ErrorSurface -> {
                taskTerminated = true
                patch { copy(error = event.message) }
            }
            is StreamEvent.FinishReason, is StreamEvent.StreamDone -> taskTerminated = true
        }
    }

    /** Chat id revealed by the server: persist the optimistic pair + chat row. */
    private suspend fun adoptLearnedChat(chatId: String, assistantMessageId: String) {
        if (_state.value.chatId != null) return // already known (multi-frame safety)
        _state.value = _state.value.copy(chatId = chatId)
        joinChatRoom(chatId)

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

        // §6.3 / 0.9-compat: the outlet filters run INLINE on current servers
        // (both paths, middleware.py:6377); the completed call remains for
        // pre-0.10 (server <0.9 ran them twice otherwise). The tree refresh
        // runs ALWAYS (a failing completed call must not skip it — the
        // fresh-chat Room row bug, 2026-11-03).
        if (chatId != null) {
            val inline = runCatching {
                val v = store.serverVersion()?.split('.')?.mapNotNull { it.toIntOrNull() }
                if (v != null && v.size >= 2) v[0] > 0 || v[1] >= 10 else false
            }.getOrDefault(false)
            if (!inline) {
                runCatching {
                    val completed = api.chatCompleted(completedBody(chatId, assistantMessageId))
                    completedAdopt(completed)
                }
            }
            runCatching {
                if (store.refreshServerChat(chatId)) {
                    paintFromStore(chatId)
                }
                // §4: post-turn tree save = the web's per-send saveChatHandler —
                // the streamed turn's node arrives parent-less server-side; the
                // merged Room tree (the local parent outranks the server null)
                // now pushes the corrected chain back (forks stay branched).
                if (pendingTreeSave && assistantMessageId != null) {
                    store.saveServerTree(chatId, assistantMessageId, activeModelId)
                }
                pendingTreeSave = false
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

    private suspend fun completedBody(chatId: String, assistantMessageId: String): String {
        // Same dict shape as the completion request (verified: utils/chat.py:319+
        // chat_completed requires id + model AND reads data['session_id'] — :353 —
        // so the session id rides even when no socket is up ('' works there).
        val body: JsonElement = buildJsonObject {
            put("model", activeModelId.orEmpty())
            put("id", assistantMessageId)
            put("chat_id", chatId)
            put("stream", false)
            put("session_id", socket?.sessionId().orEmpty())
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
        // An output ITEM {type:'message', content:[{type:'output_text', text:…}]}
        // (task sockets/Responses fold) — or a bare parts array from some upstreams.
        val obj = this as? JsonObject
        val parts = when {
            obj != null -> (obj["content"] as? JsonArray)?.toList()
            this is JsonArray -> toList()
            else -> null
        } ?: return null
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

    private fun describe(t: Throwable): String =
        (t as? Exception)?.let(::describe) ?: (t.message ?: t.javaClass.simpleName)


    private fun JsonPrimitive.jsonBool(): Boolean = content == "true"

    companion object {
        private const val CHUNK_BUFFER = 8192
        private const val ROOM_WRITE_INTERVAL_MS = 750L
        private const val SYNC_DEBOUNCE_MS = 1_000L
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