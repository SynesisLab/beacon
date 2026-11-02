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
) {
    val hasFeatures: Boolean get() = features != null
}

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
    val enable_web_search: Boolean? = null,
    val enable_image_generation: Boolean? = null,
    val enable_title_generation: Boolean? = null,
    val enable_tags_generation: Boolean? = null,
    val enable_follow_up_generation: Boolean? = null,
    val enable_direct_connections: Boolean? = null,
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
) {
    /** Non-null for success (ServerUserResponse flavor); MFA/pending responses have no token. */
    val isTokenSignIn: Boolean get() = token != null
    val isMfaChallenge: Boolean get() = next_step != null && next_step != "pending"
    val isPendingUser: Boolean get() = next_step == "pending"
}

/** GET /api/v1/models/list → routers/models.py:227-241 ({items, total}). */
@Serializable
data class ModelsPage(
    val items: List<OwuiModel> = emptyList(),
    val total: Int = 0,
)

/** ModelModel (models/models.py:247+) — client reads id/name; meta/info stay server-side in M0. */
@Serializable
data class OwuiModel(
    val id: String,
    val name: String,
)