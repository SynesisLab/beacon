package dev.beacon.core.api

import dev.beacon.core.network.await
import dev.beacon.core.network.decodeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Terminal servers (agent.md §1: terminal feature — inline FILE viewing promoted
 * into M2 by user request).
 *
 * Verified contract (open-webui routers/terminals.py):
 * - `GET /api/v1/terminals/` (user-scoped) → connections Beacon may use
 *   `{id, url, name, contexts, config}` — no secret ever leaves the server;
 * - file access is proxied: `GET /api/v1/terminals/{server_id}/files/read?path=…`
 *   (the OWUI backend holds the API key; the client authenticates as the user).
 */
@Serializable
data class TerminalServer(
    val id: String = "",
    val url: String = "",
    val name: String = "",
)

@Singleton
class TerminalsApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val source: InstanceSource,
) {
    suspend fun servers(): List<TerminalServer> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(joinUrl(requireInstance(), "/api/v1/terminals/"))
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            return@withContext emptyList() // gate: terminal not exposed/directly admin-only
        }
        val list = response.decodeJson(kotlinx.serialization.builtins.ListSerializer(TerminalServer.serializer()), json)
        list.filter { it.enabledByConfig() }
    }

    /** GET /files/read?path=… → {content} for text payloads (JSON body). */
    suspend fun readText(serverId: String, path: String): String? = withContext(Dispatchers.IO) {
        val response = proxied(serverId, path)
        try {
            if (!response.isSuccessful) return@withContext null
            val contentType = response.header(CONTENT_TYPE).orEmpty()
            if (contentType.startsWith("image/") || contentType.startsWith("application/octet")) {
                null // binary: caller should use readBytes
            }
            val jsonBody = response.body?.string() ?: return@withContext null
            (json.parseToJsonElement(jsonBody) as? JsonObject)?.get("content")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } finally {
            response.close()
        }
    }

    /** Bytes for image previews (content-type-driven). */
    suspend fun readBytes(serverId: String, path: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val response = proxied(serverId, path)
        if (!response.isSuccessful) {
            response.close()
            return@withContext null
        }
        val mime = response.header(CONTENT_TYPE) ?: "application/octet-stream"
        val bytes = response.body?.bytes()
        response.close()
        bytes?.takeIf { it.isNotEmpty() }?.let { Pair(it, mime) }
    }

    private fun proxied(serverId: String, path: String): Response {
        val encoded = URLEncoder.encode(path, "UTF-8")
        val request = Request.Builder()
            .url(joinUrl(requireInstance(), "/api/v1/terminals/$serverId/files/read?path=$encoded"))
            .get()
            .build()
        return client.newCall(request).execute()
    }

    private fun TerminalServer.enabledByConfig() = true

    private fun requireInstance(): String =
        source.instanceUrl()
            ?: throw OwuiApiException(0, null, "No connected instance")

    companion object {
        private const val CONTENT_TYPE = "Content-Type"
    }
}