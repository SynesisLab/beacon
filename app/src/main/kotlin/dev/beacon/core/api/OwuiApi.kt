package dev.beacon.core.api

import dev.beacon.core.api.dto.AppConfig
import dev.beacon.core.api.dto.ModelsPage
import dev.beacon.core.api.dto.MfaSetupData
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

    /**
     * MFA challenge steps (routers/mfa.py mounted at /api/v1/auths/mfa,
     * main.py:890). The challenge_token rides the FORM as a plain string —
     * the web's mfa_challenge cookie is not required for API clients
     * (MfaChallengeForm auths.py:150-152; verify/confirm take MfaVerifyForm
     * :155-157).
     */
    private suspend fun mfaPost(
        instanceUrl: String,
        path: String,
        payload: kotlinx.serialization.json.JsonObject,
    ) = Request.Builder()
        .url(joinUrl(instanceUrl, "/api/v1/auths/mfa$path"))
        .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)
            .toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    /** POST /verify {challenge_token, code, recovery} → SessionUserResponse
     *  (mfa.py:147). recovery=true consumes a one-time recovery code. */
    suspend fun verifyMfa(
        instanceUrl: String,
        challengeToken: String,
        code: String,
        recovery: Boolean = false,
    ): SignInData = withContext(Dispatchers.IO) {
        client.newCall(mfaPost(instanceUrl, "/verify", buildJsonObject {
            put("challenge_token", kotlinx.serialization.json.JsonPrimitive(challengeToken))
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
            put("recovery", kotlinx.serialization.json.JsonPrimitive(recovery))
        })).await().decodeJson<SignInData>(json)
    }

    /** POST /enroll/start {challenge_token} → {manual_key, qr_code}
     *  (mfa.py:129-134; setup_details utils/mfa.py:259-266 — the qr_code is
     *  an svg+xml base64 data URI). */
    suspend fun startMfaEnroll(instanceUrl: String, challengeToken: String): MfaSetupData =
        withContext(Dispatchers.IO) {
            client.newCall(mfaPost(instanceUrl, "/enroll/start", buildJsonObject {
                put("challenge_token", kotlinx.serialization.json.JsonPrimitive(challengeToken))
            })).await().decodeJson<MfaSetupData>(json)
        }

    /** POST /enroll/confirm {challenge_token, code, recovery} →
     *  SessionUserResponse + recovery_codes (MfaEnrollmentResponse
     *  auths.py:146-148; route mfa.py:134-135). */
    suspend fun confirmMfaEnroll(
        instanceUrl: String,
        challengeToken: String,
        code: String,
        recovery: Boolean = false,
    ): SignInData = withContext(Dispatchers.IO) {
        client.newCall(mfaPost(instanceUrl, "/enroll/confirm", buildJsonObject {
            put("challenge_token", kotlinx.serialization.json.JsonPrimitive(challengeToken))
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
            put("recovery", kotlinx.serialization.json.JsonPrimitive(recovery))
        })).await().decodeJson<SignInData>(json)
    }

    /** GET /api/v1/configs/models/defaults → {DEFAULT_MODEL_METADATA{
     *  capabilities, defaultFeatureIds?, builtinTools?}} — USER-readable
     *  (routers/configs.py:780-784; the admin writes it via setModelsConfig
     *  :792-…). 404s on older servers → null. */
    suspend fun globalDefaultFeatureIds(): List<String>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(joinUrl(instance(), "/api/v1/configs/models/defaults"))
                .get().build()
            val obj = client.newCall(request).await().decodeJson<JsonObject>(json)
            val meta = obj["DEFAULT_MODEL_METADATA"] as? kotlinx.serialization.json.JsonObject
                ?: return@withContext null
            (meta["defaultFeatureIds"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } catch (_: Exception) {
            null
        }
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

    /**
     * ResolveToolCallForm {call_id, action: approve|reject|answer, answers?,
     * timed_out?} (utils/tool_approval.py:16-20). For the model's ask_user:
     * action='answer' + {questionId: label}; the server appends the
     * function_call_output and RESUMES the completion — this call returns
     * whatever chat_completion returns (an SSE stream or the {task_ids}
     * JSON), so the CALLER drains it like a normal send.
     */
    fun resolveToolCall(
        chatId: String,
        messageId: String,
        callId: String,
        action: String,
        answers: Map<String, String>? = null,
        timedOut: Boolean = false,
    ): Call {
        val payload = buildJsonObject {
            put("call_id", kotlinx.serialization.json.JsonPrimitive(callId))
            put("action", kotlinx.serialization.json.JsonPrimitive(action))
            answers?.let { map ->
                put("answers", buildJsonObject {
                    map.forEach { (id, label) -> put(id, kotlinx.serialization.json.JsonPrimitive(label)) }
                })
            }
            if (timedOut) put("timed_out", kotlinx.serialization.json.JsonPrimitive(true))
        }
        return client.newCall(
            Request.Builder()
                .url(joinUrl(instance(), "/api/v1/chats/$chatId/messages/$messageId/resolve"))
                .post(json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload)
                    .toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build(),
        )
    }

    /** The one client (WebView panes reuse its same-origin auth interceptor, §11). */
    fun publicClient(): OkHttpClient = client

    @kotlinx.serialization.Serializable
    private data class SignInBody(val email: String, val password: String)
}