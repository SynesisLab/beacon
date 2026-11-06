package dev.beacon.core.api.dto

import kotlinx.serialization.Serializable

/**
 * M0 protocol DTOs. Every field name here is verified against the open-webui
 * clone (see docs/protocol-notes.md §verification-table for file:line proof) and
 * decoded tolerantly (`ignoreUnknownKeys`) — the web UI owns schema velocity,
 * the client is a guest.
 */

/** GET /api/version → open-webui backend/open_webui/main.py:2701-2706. */
@Serializable
data class VersionInfo(
    val version: String? = null,
    val deployment_id: String? = null,
)

/**
 * GET /api/config → main.py:2289-2470+. Only the fields Beacon consumes are
 * decoded; unknown keys are ignored so newer servers never break the client.
 */
@Serializable
data class AppConfig(
    val status: Boolean? = null,
    val name: String? = null,
    val version: String? = null,
    val onboarding: Boolean? = null,
    val features: Features? = null,
    val default_models: String? = null,
    /** ui.* — stores/index.ts:437-446 (iframe_csp = the admin's artifact-embed CSP). */
    val ui: UiConfig? = null,
    /** ui.prompt_suggestions → /api/config default_prompt_suggestions (main.py:2505;
     *  web renders ≤3 as chips on the empty-chat placeholder, ChatPlaceholder.svelte). */
    val default_prompt_suggestions: List<PromptSuggestion>? = null,
) {
    val hasFeatures: Boolean get() = features != null
}

@Serializable
data class PromptSuggestion(
    val title: String,
    val content: String,
)

@Serializable
data class UiConfig(
    val iframe_csp: String? = null,
)

/** features.* keys verified in main.py features block (M0 subset). */
@Serializable
data class Features(
    // Pre-auth (main.py: "Public: required by login/signup page pre-auth")
    val auth: Boolean? = null,
    val auth_trusted_header: Boolean? = null,
    val enable_signup: Boolean? = null,
    val enable_login_form: Boolean? = null,
    val enable_ldap: Boolean? = null,
    val enable_websocket: Boolean? = null,
    // Post-auth gates (capability flags; hide screens the server doesn't expose — agent.md §16.6)
    val enable_api_keys: Boolean? = null,
    val enable_folders: Boolean? = null,
    val enable_channels: Boolean? = null,
    val enable_notes: Boolean? = null,
    val enable_memories: Boolean? = null,
    val enable_web_search: Boolean? = null,
    val enable_image_generation: Boolean? = null,
    val enable_title_generation: Boolean? = null,
    val enable_tags_generation: Boolean? = null,
    val enable_follow_up_generation: Boolean? = null,
    val enable_direct_connections: Boolean? = null,
    // gates verified: main.py:2470-2471 (+ main.py's features block)
    val enable_code_execution: Boolean? = null,
    val enable_code_interpreter: Boolean? = null,
    val enable_tools: Boolean? = null,
    val enable_functions: Boolean? = null,
    val enable_tool_servers: Boolean? = null,
)

/**
 * POST /api/v1/auths/signin → auths.py:663 (`SigninResult` union,
 * models/auths.py:96-133). Discriminated manually: a token = success,
 * a next_step = MFA challenge (`enroll|verify|recover`) or pending user.
 */
@Serializable
data class SignInData(
    val token: String? = null,
    val token_type: String? = null,
    val next_step: String? = null,
    val challenge_token: String? = null,
    val expires_in: Int? = null,
    // SessionUserResponse user fields
    val id: String? = null,
    val name: String? = null,
    val email: String? = null,
    val role: String? = null,
    val profile_image_url: String? = null,
    val expires_at: Long? = null,
    /** enroll/confirm only: the one-time recovery codes (MfaEnrollmentResponse). */
    val recovery_codes: List<String>? = null,
) {
    /** Non-null for success (ServerUserResponse flavor); MFA/pending responses have no token. */
    val isTokenSignIn: Boolean get() = token != null
    val isMfaChallenge: Boolean get() = next_step != null && next_step != "pending"
    val isPendingUser: Boolean get() = next_step == "pending"
}

/** POST mfa/enroll/start → {manual_key, qr_code} (MfaSetupResponse,
 *  models/auths.py:137-139); qr_code = a data:image/svg+xml;base64 URI
 *  (utils/mfa.py:259-266). */
@Serializable
data class MfaSetupData(
    val manual_key: String,
    val qr_code: String? = null,
)

/** GET /api/v1/models/list → routers/models.py:227-241 ({items, total}). */
@Serializable
data class ModelsPage(
    val items: List<OwuiModel> = emptyList(),
    val total: Int = 0,
)

/** ModelModel (models/models.py:199-217): the /api/v1/models/list item keeps
 *  meta at the TOP LEVEL (the DB row's meta JSON — ModelMeta), while the
 *  chat-registry /api/models shape nests it under info.meta. Both tolerated;
 *  description verified models/models.py:144-146. */
@Serializable
data class OwuiModel(
    val id: String,
    val name: String,
    val info: OwuiModelInfo? = null,
    val meta: OwuiModelMeta? = null,
) {
    val description: String? get() = info?.meta?.description ?: meta?.description

    /** The server-configured default features for this model
     *  (Chat.svelte:1114-1118: a feature starts ON when every selected model
     *  lists it in defaultFeatureIds; admin checks in utils/automations.py:186). */
    val default_feature_ids: List<String>
        get() = info?.meta?.defaultFeatureIds ?: meta?.defaultFeatureIds ?: emptyList()

    /** The model's default TOOLS/SKILLS selections (Chat.svelte:1121-1135) —
     *  same both-shapes fallback as the features. */
    val default_tool_ids: List<String>
        get() = info?.meta?.toolIds ?: meta?.toolIds ?: emptyList()

    val default_skill_ids: List<String>
        get() = info?.meta?.skillIds ?: meta?.skillIds ?: emptyList()
}

@Serializable
data class OwuiModelInfo(
    val meta: OwuiModelMeta? = null,
)

@Serializable
data class OwuiModelMeta(
    val description: String? = null,
    val capabilities: Capabilities? = null,
    val defaultFeatureIds: List<String>? = null,
    /** The model's default workspace selections (Chat.svelte:1121-1142). */
    val toolIds: List<String>? = null,
    val skillIds: List<String>? = null,
    val defaultFilterIds: List<String>? = null,
)

@Serializable
data class Capabilities(
    val web_search: Boolean? = null,
    val image_generation: Boolean? = null,
    val code_interpreter: Boolean? = null,
)

/** GET /api/v1/auths/ — session user (auths.py:178; SessionUserInfoResponse). */
@Serializable
data class SessionUser(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null,
    val role: String? = null,
    val profile_image_url: String? = null,
)