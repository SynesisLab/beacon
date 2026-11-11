package dev.beacon.core.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * POST /api/chat/completions request body — M1 field set, verified in
 * docs/protocol-notes.md (Chat.svelte:4420-4495 + main.py:1255-1278):
 *
 * - `parent_id` null = new chat root; a value = follow-up (main.py:1255-1258);
 * - `id` = client-generated assistant message id (multi-model list flattened to
 *   the single-model `{model_id, message_id}` server fallback path);
 * - `session_id` = the socket session id (Chat.svelte:3281 `$socket?.id`). With
 *   session_id + chat_id the server runs the completion as a background task
 *   (middleware.py:2108, main.py:1813-1930): the HTTP body is
 *   {status, task_ids, chat_id} (main.py:1916) and the stream rides socket
 *   `events` (socket/main.py:1626-1673). Omitted → the server streams SSE.
 * - `background_tasks.title/tags_generation` only for a genuinely fresh chat
 *   (Chat.svelte:4489-4497 condition: not temporary && (no chat || first pair));
 * - `messages` = the visible history projection of the current branch.
 */
@Serializable
data class CompletionRequest(
    val stream: Boolean = true,
    val model: String,
    val messages: List<ProjectionMessage> = emptyList(),
    val chat_id: String? = null,
    val id: String,
    val parent_id: String? = null,
    /** The socket session id when connected; null = plain SSE (M1 path). */
    val session_id: String? = null,
    /** The client-side user turn dict (id/parentId/role/content/timestamp —
     *  Chat.svelte:3375-3385); a JsonElement because extra keys ride along. */
    val user_message: JsonElement? = null,
    val files: List<FileAttachment> = emptyList(),
    /** Regenerate: the sibling assistant being replaced (Chat.svelte:4472-4477). */
    val regeneration_message_id: String? = null,
    val regeneration_prompt: String? = null,
    /** Continue: append to this assistant turn (Chat.svelte:4478). */
    val assistant_message_id: String? = null,
    val features: Features = Features(),
    /** Request `params` — carries `reasoning_effort` (Chat.svelte:4420-4495;
     *  docs/interface-mapping.md). Null = server default. */
    val params: Params? = null,
    val tool_ids: List<String> = emptyList(),
    val filter_ids: List<String> = emptyList(),
    val skill_ids: List<String> = emptyList(),
    val background_tasks: BackgroundTasks = BackgroundTasks(),
)

/** File attachments ride the request's `files` (verified: Chat.svelte's body w/ {id, type:'file', name}). */
@Serializable
data class FileAttachment(
    val id: String,
    val type: String = "file",
    val name: String,
)

@Serializable
data class ProjectionMessage(
    val role: String,
    val content: String,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        fun user(content: String) = ProjectionMessage(role = ROLE_USER, content = content)
        fun assistant(content: String) = ProjectionMessage(role = ROLE_ASSISTANT, content = content)
    }
}

/** The request's `params` dict (verified key subset — Chat.svelte:4420-4495). */
@Serializable
data class Params(
    val reasoning_effort: String? = null,
)

@Serializable
data class Features(
    val image_generation: Boolean = false,
    val web_search: Boolean = false,
    val code_interpreter: Boolean = false,
)

/** Chat.svelte:4489-4497 — title/tags only when fresh chat; follow-ups per setting. */
@Serializable
data class BackgroundTasks(
    val title_generation: Boolean = false,
    val tags_generation: Boolean = false,
    val follow_up_generation: Boolean = false,
)

object CompletionRequests {
    private val requestJson = Json {
        // stream=true must always serialize (server-side default is unspecified);
        // nulls (chat_id on fresh chats) stay omitted; empty lists ride along harmlessly.
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(request: CompletionRequest): String =
        requestJson.encodeToString(CompletionRequest.serializer(), request)
}