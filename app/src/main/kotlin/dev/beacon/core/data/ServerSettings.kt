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
    /** Opt-in: trust user-added CAs (self-signed labs). */
    val trustUserCa: Boolean = false,
    val serverVersion: String? = null,
    val selectedModelId: String? = null,
    val signedInEmail: String? = null,
    /** Display name for the home greeting (GET /api/v1/auths/). */
    val userName: String? = null,
    /** Role badge for the sidebar's identity bar (sessionUser().role). */
    val userRole: String? = null,
    /** Forced light/dark or follow-the-system (user request 2026-11-03). */
    val themeMode: ThemeMode = ThemeMode.System,
    /** Per-session reasoning effort sent as request `params.reasoning_effort`
     *  (wire-verified: docs/interface-mapping.md; null/blank = server default). */
    val reasoningEffort: String? = null,
    /** App lock (Settings → Security): require device credentials when the
     *  app opens. Pure client-side; default off. */
    val appLock: Boolean = false,
    /** Boot-flash cache (user request 2026-11-06): last-known model display
     *  names {"id":"name",…} + the chip default/gate csv — the Web/Image/Code
     *  chips and the resolved NAME paint at boot instead of popping in
     *  seconds later when the defaults chain lands. Network refresh wins. */
    val modelNamesJson: String? = null,
    val cachedChipDefaults: String? = null,
    val cachedChipGates: String? = null,
    /**
     * Per-row sidebar visibility (user request 2026-11-06): NAV rows the user
     * toggled OFF ("notes", "files", "workspace", "memories", "channels",
     * "settings"). Null = defaults visible. Chats is the home scaffold and
     * never hides; gated rows still need their /api/config flags.
     */
    val hiddenNavRows: List<String>? = null,
) {
    val isOnboarded: Boolean get() = serverUrl.isNotBlank() && authMethod != AuthMethod.None

    val hiddenNavRowsSet: Set<String> get() = hiddenNavRows?.toSet() ?: emptySet()
}

/** csv codec for the cache fields (ids among web/image/code). */
fun parseChipCsv(raw: String?): Set<String> =
    raw?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

fun chipsCsv(ids: Collection<String>): String = ids.joinToString(",")

/** ServerSettings model-name lookup against the boot cache ({"id":"name"} json). */
fun ServerSettings.modelNameFor(modelId: String?): String? {
    if (modelId.isNullOrBlank()) return null
    val raw = modelNamesJson?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw)
        (obj as? kotlinx.serialization.json.JsonObject)?.get(modelId)
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()
}

/**
 * Normalizes a user-typed instance URL into an origin (scheme://host[:port], no trailing slash).
 * Adds https:// when no scheme is present (matches the usual connection-form behavior).
 */
fun normalizeInstanceUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    // The scheme acceptor is case-insensitive: mobile keyboards auto-capitalize
    // and a pasted "Http://…" (or "HTTP://…") must not fall through to the
    // "no scheme" branch, which would yield "https://Http://host[:port]".
    val lower = trimmed.lowercase()
    if (lower.startsWith("http://")) {
        return "http://" + trimmed.substring("http://".length)
    }
    if (lower.startsWith("https://")) {
        return "https://" + trimmed.substring("https://".length)
    }
    return "https://$trimmed"
}