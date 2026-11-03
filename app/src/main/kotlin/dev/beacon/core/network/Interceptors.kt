package dev.beacon.core.network

import dev.beacon.core.data.SessionStore
import dev.beacon.core.data.sameOrigin
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

private const val AUTHORIZATION = "Authorization"

/**
 * Single place encoding the credential/rules from agent.md §11:
 * auth header + user headers attach to same-origin requests only; never to
 * third-party hosts (HtmlCard WebViews and previews reuse this client).
 */
internal fun Request.attachInstanceAuth(
    instanceUrl: String?,
    bearer: String?,
    headers: Map<String, String>,
): Request {
    if (instanceUrl.isNullOrBlank() || !sameOrigin(instanceUrl, url.toString())) return this

    val builder = newBuilder()
    if (bearer != null) {
        builder.header(AUTHORIZATION, "Bearer $bearer")
    }
    headers.forEach { (name, value) ->
        if (name.isNotBlank()) {
            builder.header(name, value)
        }
    }
    return builder.build()
}

/** Pure-JVM-friendly Interceptor for tests: rules identical to [AuthInterceptor]. */
@Suppress("FunctionName")
fun TestInterceptor(instanceUrl: String?, bearer: String?, headers: Map<String, String> = emptyMap()): Interceptor =
    Interceptor { chain ->
        chain.proceed(chain.request().attachInstanceAuth(instanceUrl, bearer, headers))
    }

@Singleton
class AuthInterceptor @Inject constructor(
    private val session: SessionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val instanceUrl = session.instanceUrlSnapshot()
        val bearer = instanceUrl?.let { session.credentialFor(it) }
        val response = chain.proceed(
            chain.request().attachInstanceAuth(instanceUrl, bearer, session.settingsSnapshot().customHeaders),
        )
        // A 401 on a call that CARRIED credentials = dead token/key (M4
        // session-expiry surfacing). Permission denials ride 403, sign-in
        // sends no bearer — so this stays a fair proxy for expiry.
        if (response.code == 401 && bearer != null) {
            session.markAuthExpired()
        }
        return response
    }
}

/**
 * §16.7 evidence loop: one log line per REST call (method URL → status/error),
 * always on (cheap, logcat-only). Sse/socket traffic stays out (frames log via
 * the parser's own counters). `response.peekBody` — the response reads on.
 */
class HttpTraceInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request).also { response ->
                if (!response.isSuccessful) {
                    val excerpt = runCatching { response.peekBody(300).string() }.getOrNull()
                    android.util.Log.w("BeaconHttp", "← ${response.code} ${request.url.encodedPath} ${excerpt?.take(200)}")
                } else {
                    android.util.Log.i("BeaconHttp", "→ ${request.method} ${request.url.encodedPath} ← ${response.code}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BeaconHttp", "✗ ${request.method} ${request.url.encodedPath} ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }
}