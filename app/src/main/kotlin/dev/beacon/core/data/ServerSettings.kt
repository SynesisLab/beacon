package dev.beacon.core.data

import kotlinx.serialization.Serializable

@Serializable
enum class AuthMethod {
    None, ApiKey, Password
}

/** The user's theme override (Settings → Appearance). */
@Serializable
enum class ThemeMode { System, Light, Dark }

/** Device-side server configuration (no secrets — those live in SecureStore only). */
@Serializable
data class ServerSettings(
    val serverUrl: String = "",
    val authMethod: AuthMethod = AuthMethod.None,
    /** Static headers added by the user (org-routing, gateway keys) — sent to the instance only. */
    val customHeaders: Map<String, String> = emptyMap(),
    /** Opt-in: trust user-added CAs (self-signed labs). agent.md §5.5. */
    val trustUserCa: Boolean = false,
    val serverVersion: String? = null,
    val selectedModelId: String? = null,
    val signedInEmail: String? = null,
    /** Display name for the home greeting (GET /api/v1/auths/). */
    val userName: String? = null,
    /** Forced light/dark or follow-the-system (user request 2026-11-03). */
    val themeMode: ThemeMode = ThemeMode.System,
) {
    val isOnboarded: Boolean get() = serverUrl.isNotBlank() && authMethod != AuthMethod.None
}

/**
 * Normalizes a user-typed instance URL into an origin (scheme://host[:port], no trailing slash).
 * Adds https:// when no scheme is present (matches Conduit's connection-form behavior).
 */
fun normalizeInstanceUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}