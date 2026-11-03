package dev.beacon.core.api

import android.net.Uri
import dev.beacon.core.network.await
import dev.beacon.core.network.decodeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TerminalServer(val id: String, val url: String = "", val name: String = "")

@Serializable
data class TsFileEntry(
    val name: String,
    val path: String,
    @kotlinx.serialization.json.JsonNames("isDirectory") val is_dir: Boolean = false,
    val size: Long? = null,
    val modified_at: Long? = null,
) {
    val isDirectory: Boolean get() = is_dir
}

@Serializable
data class TsPort(val port: Int, val pid: Int? = null, val process: String? = null)

/** A read: decoded text ({content: …}) or raw bytes with a mime type. */
sealed interface TsRead {
    data class Text(val text: String) : TsRead
    data class Raw(val bytes: ByteArray, val mime: String) : TsRead
}

/** Terminal-server FILES surface — Conduit parity, ORIGINAL implementation
 *  (GPL: behavior read as spec, code ours). Calls ride the OWUI proxy
 *  /api/v1/terminals/{server}/… (routers/terminals.py:112+). */
@Singleton
class TerminalsApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val source: InstanceSource,
) {
    private val jsonBody = "application/json; charset=utf-8".toMediaTypeOrNull()

    private fun url(serverId: String, path: String, query: String = ""): Request.Builder {
        val base = source.instanceUrl() ?: throw OwuiApiException(0, null, "No connected instance")
        return Request.Builder().url(joinUrl(base, "/api/v1/terminals/$serverId/$path$query"))
    }

    suspend fun servers(): List<TerminalServer> = withContext(Dispatchers.IO) {
        client.newCall(url("", "").build()).await().decodeJson(
            ListSerializer(TerminalServer.serializer()), json,
        )
    }

    suspend fun cwd(serverId: String): String? = withContext(Dispatchers.IO) {
        client.newCall(url(serverId, "files/cwd").get().build()).await().use { r ->
            if (!r.isSuccessful) return@withContext null
            (json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject)
                ?.get("cwd")?.let { (it as? JsonPrimitive)?.content }
        }
    }

    suspend fun setCwd(serverId: String, path: String) = withContext(Dispatchers.IO) {
        client.newCall(
            url(serverId, "files/cwd")
                .post(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("path", path) }).toRequestBody(jsonBody))
                .build(),
        ).await().use { }
    }

    suspend fun list(serverId: String, directory: String): List<TsFileEntry> = withContext(Dispatchers.IO) {
        client.newCall(url(serverId, "files/list", "?directory=" + Uri.encode(directory)).get().build())
            .await().use { r ->
                if (!r.isSuccessful) return@withContext emptyList()
                val obj = json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
                    ?: return@withContext emptyList()
                val items = (obj["items"] ?: obj["entries"]) as? kotlinx.serialization.json.JsonArray
                    ?: return@withContext emptyList()
                json.decodeFromJsonElement(ListSerializer(TsFileEntry.serializer()), items)
            }
    }

    suspend fun read(serverId: String, path: String): TsRead? = withContext(Dispatchers.IO) {
        client.newCall(url(serverId, "files/read", "?path=" + Uri.encode(path)).get().build()).await().use { r ->
            if (!r.isSuccessful) return@withContext null
            val mime = r.header("Content-Type") ?: "application/octet-stream"
            if (mime.contains("application/json")) {
                val obj = json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
                TsRead.Text(obj?.get("content")?.let { (it as? JsonPrimitive)?.content }.orEmpty())
            } else {
                TsRead.Raw(r.body?.bytes() ?: ByteArray(0), mime)
            }
        }
    }

    suspend fun view(serverId: String, path: String): Pair<String, TsRead>? = withContext(Dispatchers.IO) {
        client.newCall(url(serverId, "files/view", "?path=" + Uri.encode(path)).get().build()).await().use { r ->
            if (!r.isSuccessful) return@withContext null
            val name = Regex("filename=\"?([^\";]+)\"?").find(r.header("Content-Disposition").orEmpty())
                ?.groupValues?.get(1) ?: path.substringAfterLast('/')
            name to TsRead.Raw(r.body?.bytes() ?: ByteArray(0), r.header("Content-Type") ?: "application/octet-stream")
        }
    }

    suspend fun upload(serverId: String, directory: String, fileName: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val multipart = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    okhttp3.RequestBody.create("application/octet-stream".toMediaTypeOrNull(), bytes),
                ).build()
            client.newCall(url(serverId, "files/upload").post(multipart).header("X-Directory", directory).build())
                .await().use { it.isSuccessful }
        }

    suspend fun mkdir(serverId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            url(serverId, "files/mkdir")
                .post(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("path", path) }).toRequestBody(jsonBody))
                .build(),
        ).await().use { it.isSuccessful }
    }

    suspend fun delete(serverId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(url(serverId, "files/delete", "?path=" + Uri.encode(path)).delete().build())
            .await().use { it.isSuccessful }
    }

    suspend fun move(serverId: String, source: String, destination: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(
            url(serverId, "files/move")
                .post(json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { put("source", source); put("destination", destination) },
                ).toRequestBody(jsonBody))
                .build(),
        ).await().use { it.isSuccessful }
    }

    suspend fun ports(serverId: String): List<TsPort> = withContext(Dispatchers.IO) {
        client.newCall(url(serverId, "ports").get().build()).await().use { r ->
            if (!r.isSuccessful) return@withContext emptyList()
            val obj = json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonObject
                ?: return@withContext emptyList()
            val items = obj["ports"] as? kotlinx.serialization.json.JsonArray
                ?: return@withContext emptyList()
            json.decodeFromJsonElement(ListSerializer(TsPort.serializer()), items)
        }
    }
}