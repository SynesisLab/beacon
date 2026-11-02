package dev.beacon.core.api

import dev.beacon.core.api.dto.AppConfig
import dev.beacon.core.api.dto.ModelsPage
import dev.beacon.core.api.dto.SignInData
import dev.beacon.core.api.dto.VersionInfo
import dev.beacon.core.data.SessionStore
import dev.beacon.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Typed failure surface for the UI (agent.md: never crash on server variance). */
class OwuiApiException(
    val code: Int,
    val bodyExcerpt: String?,
    message: String,
) : IOException(message)

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
    private val session: SessionStore,
) {
    suspend fun probeVersion(baseUrl: String): VersionInfo = withContext(Dispatchers.IO) {
        val request = request(joinUrl(baseUrl, "/api/version")).get().build()
        client.newCall(request).await().decodeJson<VersionInfo>(json)
    }

    suspend fun config(): AppConfig = withContext(Dispatchers.IO) {
        val request = request(joinUrl(requireInstance(), "/api/config")).get().build()
        client.newCall(request).await().decodeJson<AppConfig>(json)
    }

    /** SignInForm email/password → SigninResult union (token | MFA challenge | pending). */
    suspend fun signIn(instanceUrl: String, email: String, password: String): SignInData =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                SignInBody.serializer(),
                SignInBody(email, password),
            ).toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = request(joinUrl(instanceUrl, "/api/v1/auths/signin")).post(body).build()
            client.newCall(request).await().decodeJson<SignInData>(json)
        }

    suspend fun models(): ModelsPage = withContext(Dispatchers.IO) {
        val request = request(joinUrl(requireInstance(), "/api/v1/models/list?page=1")).get().build()
        val page = client.newCall(request).await().decodeJson<ModelsPage>(json)
        page.copy(items = page.items.distinctBy { it.id })
    }

    internal fun joinUrl(base: String, path: String): String {
        val trimmedBase = base.trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return trimmedBase + cleanPath
    }

    private fun requireInstance(): String =
        session.instanceUrlSnapshot()
            ?: throw OwuiApiException(0, null, "No connected instance")

    private fun request(url: String): Request.Builder = Request.Builder().url(url)

    private suspend inline fun <reified T> Response.decodeJson(json: Json): T = use {
        if (!isSuccessful) {
            val excerpt = body?.string()?.take(400)
            throw OwuiApiException(code, excerpt, "HTTP $code ${message.orEmpty()}".trim())
        }
        val text = body?.string() ?: throw OwuiApiException(code, null, "Empty body")
        try {
            json.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            throw OwuiApiException(code, text.take(400), "Unexpected payload shape: ${e.message}")
        }
    }

    @kotlinx.serialization.Serializable
    private data class SignInBody(val email: String, val password: String)
}