package dev.beacon.core.api

import dev.beacon.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server audio (agent.md §8):
 * - TTS: POST /api/v1/audio/speech (JSON body; the RESPONSE = audio bytes —
 *   verified routers/audio: `body = await request.body()`-decoded dict)
 * - STT: POST /api/v1/audio/transcriptions (multipart file + language)
 * - GET /api/v1/audio/models (the engines gate for read-aloud UI)
 */
@Singleton
class AudioApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val source: InstanceSource,
) {
    /** The TTS engines the server exposes (agent.md §8.4: gate read-aloud on this). */
    suspend fun models(): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(joinUrl(requireInstance(), "/api/v1/audio/models"))
                .get()
                .build()
            client.newCall(request).await().use {
                if (!it.isSuccessful) {
                    return@withContext null
                }
                it.body?.string()?.let { text -> json.parseToJsonElement(text) as? JsonObject }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Speech synthesis bytes (mp3/wav — server-decided). */
    suspend fun speech(input: String, voice: String?): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("input", input)
                voice?.let { put("voice", it) }
            }
            val request = Request.Builder()
                .url(joinUrl(requireInstance(), "/api/v1/audio/speech"))
                .post(
                    json.encodeToString(JsonObject.serializer(), body)
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
                )
                .build()
            client.newCall(request).await().use {
                if (!it.isSuccessful) {
                    return@withContext null
                }
                it.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Server transcription (admin's Whisper / OpenAI STT). */
    suspend fun transcribe(
        name: String,
        mime: String,
        bytes: ByteArray,
        language: String?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaTypeOrNull()))
                .apply { language?.let { addFormDataPart("language", it) } }
                .build()
            val request = Request.Builder()
                .url(joinUrl(requireInstance(), "/api/v1/audio/transcriptions"))
                .post(body)
                .build()
            client.newCall(request).await().use {
                if (!it.isSuccessful) {
                    return@withContext null
                }
                it.body?.string()?.let { text ->
                    (json.parseToJsonElement(text) as? JsonObject)
                        ?.get("text")?.let { t -> (t as? kotlinx.serialization.json.JsonPrimitive)?.content }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun requireInstance(): String =
        source.instanceUrl() ?: throw OwuiApiException(0, null, "No connected instance")
}