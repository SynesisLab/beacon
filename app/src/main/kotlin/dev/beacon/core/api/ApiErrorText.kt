package dev.beacon.core.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Human text from an OWUI error response. The open-webui backend is FastAPI:
 * non-2xx responses carry a JSON envelope `{"detail": "<msg>"}` — or the
 * validator form `[{"msg": "…", "loc": […]}]` — middleware error bubbles carry
 * `{"detail": {"message": "…"}}`. Beacon surfaces the server's own message
 * verbatim (a "Message Error" placeholder helps nobody; the user needs to
 * know WHY: quota, model missing, RBAC, bad key).
 */
object ApiErrorText {

    /** The server's own error message, or null when the body isn't parseable. */
    fun fromBody(rawBody: String?): String? {
        val body = rawBody?.trim().orEmpty()
        if (!body.startsWith("{") && !body.startsWith("[")) return null
        return try {
            when (val el = KJSON.parseToJsonElement(body)) {
                is JsonObject -> fromObject(el)
                is JsonArray -> fromArray(el)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fromObject(obj: JsonObject): String? {
        val detail = obj["detail"] ?: obj["message"] ?: return null
        return when (detail) {
            is JsonPrimitive -> detail.content.takeIf { it.isNotBlank() }
            is JsonObject -> (detail["message"] as? JsonPrimitive)?.content
                ?: (detail["detail"] as? JsonPrimitive)?.content
            is JsonArray -> fromArray(detail)
        }
    }

    private fun fromArray(array: JsonArray): String? =
        array
            .mapNotNull { (it as? JsonObject)?.get("msg") as? JsonPrimitive }
            .joinToString("; ") { it.content }
            .ifBlank { null }

    /**
     * Full display line for an HTTP failure: the server's message when we can
     * read it, else a status description. Prefixes keep it honest about WHERE
     * the failure happened (server vs the app's own request).
     */
    fun display(code: Int, bodyExcerpt: String?): String? = when {
        code == 0 -> null // connection-level: the caller's message is better
        else -> {
            val detail = fromBody(bodyExcerpt)
            when {
                detail != null -> "Server error $code: $detail"
                else -> null
            }
        }
    }

    private val KJSON = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
