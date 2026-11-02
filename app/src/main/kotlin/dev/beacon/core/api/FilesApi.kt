package dev.beacon.core.api

import dev.beacon.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private fun requireInstance(): String =
        source.instanceUrl()
            ?: throw OwuiApiException(0, null, "No connected instance")
}