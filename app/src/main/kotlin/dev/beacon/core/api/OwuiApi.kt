package dev.beacon.core.api

import dev.beacon.core.api.dto.AppConfig
import dev.beacon.core.api.dto.ModelsPage
import dev.beacon.core.api.dto.SignInData
import dev.beacon.core.api.dto.VersionInfo
import dev.beacon.core.data.SessionStore
import dev.beacon.core.network.await
import dev.beacon.core.network.decodeJson
import dev.beacon.core.api.dto.SessionUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Typed failure surface for the UI (agent.md: never crash on server variance). */
class OwuiApiException(
    val code: Int,
    val bodyExcerpt: String?,
    message: String,
) : IOException(message)

/** Narrow instance-origin source — tests supply MockWebServer roots directly. */
fun interface InstanceSource {
    fun instanceUrl(): String?
}

/**
 * One HTTP client for REST + SSE (agent.md §3). M0 routes:
 * /api/version, /api/config, /api/v1/auths/signin, /api/v1/models/list.
 * Shapes verified in docs/protocol-notes.md — never add an endpoint or field
 * without the open-webui clone as proof (agent.md §16.2).
 */
@Singleton
class OwuiApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val source: InstanceSource,
) {
    suspend fun probeVersion(baseUrl: String): VersionInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(joinUrl(baseUrl, "/api/version")).get().build()
        client.newCall(request).await().decodeJson<VersionInfo>(json)
    }

    suspend fun config(): AppConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(joinUrl(instance(), "/api/config")).get().build()
        client.newCall(request).await().decodeJson<AppConfig>(json)
    }

    /** SignInForm email/password → SigninResult union (token | MFA challenge | pending). */
    suspend fun signIn(instanceUrl: String, email: String, password: String): SignInData =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                SignInBody.serializer(),
                SignInBody(email, password),
            ).toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(joinUrl(instanceUrl, "/api/v1/auths/signin"))
                .post(body)
                .build()
            client.newCall(request).await().decodeJson<SignInData>(json)
        }

    /** The current user (GET /api/v1/auths/ — verified auths.py:178). */
    suspend fun sessionUser(): SessionUser = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(joinUrl(instance(), "/api/v1/auths/")).get().build()
        client.newCall(request).await().decodeJson<SessionUser>(json)
    }

    suspend fun models(): ModelsPage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(joinUrl(instance(), "/api/v1/models/list?page=1"))
            .get()
            .build()
        val page = client.newCall(request).await().decodeJson<ModelsPage>(json)
        page.copy(items = page.items.distinctBy { it.id })
    }

    // ── M1 completions surface (verified: docs/protocol-notes.md M1 table) ────

    /**
     * Builds (but does not execute) the streaming completion call — the engine
     * drains it chunk-by-chunk so [SseFrameScanner] sees every byte as it arrives.
     */
    fun streamingCompletion(body: String): Call {
        val request = Request.Builder()
            .url(joinUrl(instance(), "/api/chat/completions"))
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream, application/json")
            .build()
        return client.newCall(request)
    }

    /**
     * Outlet-filter round-trip (§6.3). Body = the same dict shape the completion
     * request carried; the response is form_data post-filters — may carry
     * rewritten messages. Returns the raw JSON for the caller to merge tolerantly.
     */
    suspend fun chatCompleted(body: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(joinUrl(instance(), "/api/chat/completed"))
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).await().decodeJson<JsonObject>(json)
    }

    /** User-level cancellation: POST /api/tasks/chat/{id}/stop (src/lib/apis/index.ts:335). */
    suspend fun stopTasksForChat(chatId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(joinUrl(instance(), "/api/tasks/chat/$chatId/stop"))
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).await().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    // ── M3 branch mutations (verified: chats.py:1510/1577; evaluations:247) ──

    /** Edit-a-message content: POST /api/v1/chats/{id}/messages/{message_id} {content}. */
    suspend fun updateMessageContent(chatId: String, messageId: String, content: String): JsonObject =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("content", content) }
            val request = Request.Builder()
                .url(joinUrl(instance(), "/api/v1/chats/$chatId/messages/$messageId"))
                .post(
                    json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body)
                        .toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
                .build()
            client.newCall(request).await().decodeJson<JsonObject>(json)
        }

    /** Rate: POST /api/v1/evaluations/feedback {type:'rating', data:{…}} (web feedbackHandler). */
    suspend fun rate(chatId: String, modelId: String?, messageId: String, rating: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildJsonObject {
                    put("type", "rating")
                    put("data", buildJsonObject {
                        put("id", messageId)
                        put("rating", rating)
                        modelId?.let { put("model_id", it) }
                        put("chat_id", chatId)
                    })
                }
                val request = Request.Builder()
                    .url(joinUrl(instance(), "/api/v1/evaluations/feedback"))
                    .post(json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload)
                        .toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).await().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }

    fun instance(): String =
        source.instanceUrl()
            ?: throw OwuiApiException(0, null, "No connected instance")

    /** The one client (WebView panes reuse its same-origin auth interceptor, §11). */
    fun publicClient(): OkHttpClient = client

    @kotlinx.serialization.Serializable
    private data class SignInBody(val email: String, val password: String)
}