package dev.beacon.core.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The user's custom HTTP headers (org-routing / gateway keys, M4):
 * validation, sanitizing and the encrypted-at-rest blob codec.
 *
 * Storage lives in SecureStore (Keystore, §11 "credentials in Keystore only")
 * under [SecureStore.HEADERS_ALIAS] — the values are gateway credentials, so
 * they must never sit in the plain-JSON settings DataStore. Wiped on sign-out.
 *
 * Rules:
 *  - field names are RFC 9110 `token`s (no whitespace/colon);
 *  - values may not contain CR/LF (injection guard);
 *  - `Authorization` is reserved — Beacon's sign-in owns it, a custom header
 *    may never override it;
 *  - `Cookie` is allowed on purpose: a reverse-proxy session cookie pasted
 *    into this editor is the simple form of proxy-cookie auth (the full
 *    WebView capture flow remains a later follow-up).
 */
object CustomHeaders {

    const val RESERVED_AUTHORIZATION = "authorization"

    /** RFC 9110 tchar set (field-name = 1*tchar). */
    private const val TOKEN_EXTRA = "!#\$%&'*+.^_`|~-"

    private val json = Json { ignoreUnknownKeys = true }

    private fun isTokenChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c in TOKEN_EXTRA

    fun isValidName(name: String): Boolean = name.isNotEmpty() && name.all(::isTokenChar)

    /** Non-empty and no CR/LF/NUL (empty values are rejected — the editor calls them Required). */
    fun isValidValue(value: String): Boolean =
        value.isNotEmpty() && value.none { it == '\r' || it == '\n' || it == '\u0000' }

    fun isReserved(name: String): Boolean = name.equals(RESERVED_AUTHORIZATION, ignoreCase = true)

    /** Drops invalid/reserved/blank entries, trimming both parts, order preserved. */
    fun sanitize(map: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((rawName, rawValue) in map) {
            val name = rawName.trim()
            val value = rawValue.trim()
            if (isValidName(name) && !isReserved(name) && isValidValue(value)) {
                out[name] = value
            }
        }
        return out
    }

    /** The SecureStore blob form of the header map (encrypted at rest by [SecureStore]). */
    fun encode(map: Map<String, String>): String = json.encodeToString(map)

    /**
     * Decodes a stored blob defensively: a damaged or hand-edited file yields
     * an empty (not throwing) map, and every entry passes [sanitize] again.
     */
    fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
        return parsed?.let(::sanitize) ?: emptyMap()
    }
}
