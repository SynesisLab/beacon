package dev.beacon.core.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * POST /api/chat/completions request body — M1 field set, verified in
 * docs/protocol-notes.md (Chat.svelte:4420-4495 + main.py:1255-1278):
 *
 * - `parent_id` null = new chat root; a value = follow-up (main.py:1255-1258);
 * - `id` = client-generated assistant message id (multi-model list flattened to
 *   the single-model `{model_id, message_id}` server fallback path);
 * - `session_id` omitted in M1 (no socket — the server then streams directly,
 *   never routes to taskSocket);
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
    val user_message: ProjectionMessage? = null,
    /** Regenerate: the sibling assistant being replaced (Chat.svelte:4472-4477). */
    val regeneration_message_id: String? = null,
    val regeneration_prompt: String? = null,
    /** Continue: append to this assistant turn (Chat.svelte:4478). */
    val assistant_message_id: String? = null,
    val features: Features = Features(),
    val tool_ids: List<String> = emptyList(),
    val filter_ids: List<String> = emptyList(),
    val skill_ids: List<String> = emptyList(),
    val background_tasks: BackgroundTasks = BackgroundTasks(),
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