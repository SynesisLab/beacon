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
    /** Opt-in: trust user-added CAs (self-signed labs). agent.md §5.5. */
    val trustUserCa: Boolean = false,
    val serverVersion: String? = null,
    val selectedModelId: String? = null,
    val signedInEmail: String? = null,
    /** Display name for the home greeting (GET /api/v1/auths/). */
    val userName: String? = null,
    /** Forced light/dark or follow-the-system (user request 2026-11-03). */
    val themeMode: ThemeMode = ThemeMode.System,
    /** Boot-flash cache (user request 2026-11-06): last-known model display
     *  names {"id":"name",…} + the chip default/gate csv — the Web/Image/Code
     *  chips and the resolved NAME paint at boot instead of popping in
     *  seconds later when the defaults chain lands. Network refresh wins. */
    val modelNamesJson: String? = null,
    val cachedChipDefaults: String? = null,
    val cachedChipGates: String? = null,
) {
    val isOnboarded: Boolean get() = serverUrl.isNotBlank() && authMethod != AuthMethod.None
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