package dev.beacon.core.api

import dev.beacon.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File content: GET /api/v1/files/{id}/content (verified: routers/files.py:813 —
 * streams bytes under the user's auth). Auth header attaches via the same
 * same-origin interceptor because this client IS the instance client.
 */
@Singleton
class FilesApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val source: InstanceSource,
) {
    /** Streams the file; caller must close. */
    suspend fun content(fileId: String): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(joinUrl(requireInstance(), "/api/v1/files/$fileId/content"))
            .get()
            .build()
        client.newCall(request).await()
    }

    /** Downloads into the app cache; returns the file path (for open-with/preview). */
    suspend fun download(fileId: String, cacheDir: File): File? = withContext(Dispatchers.IO) {
        val response = content(fileId)
        if (!response.isSuccessful) {
            response.close()
            return@withContext null
        }
        cacheDir.mkdirs()
        val dest = File(cacheDir, fileId)
        response.body?.byteStream()?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.takeIf { it.length() > 0 }
    }

    /**
     * Multipart upload: POST /api/v1/files/ — `file` field + optional JSON
     * `metadata` + `process` query (verified: routers/files.py:276,
     * src/apis/files uploadFile). Returns the server file id when accepted.
     */
    suspend fun upload(
        name: String,
        mime: String,
        bytes: ByteArray,
        metadataJson: String? = null,
        process: Boolean = true,
    ): String? = withContext(Dispatchers.IO) {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaTypeOrNull()))
            .apply {
                metadataJson?.let { addFormDataPart("metadata", it) }
            }
            .build()
        val request = Request.Builder()
            .url(joinUrl(requireInstance(), "/api/v1/files/?process=$process"))
            .post(body)
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body?.string()?.let { text ->
                (json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject)
                    ?.get("id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
        }
    }

    private fun requireInstance(): String =
        source.instanceUrl()
            ?: throw OwuiApiException(0, null, "No connected instance")
}