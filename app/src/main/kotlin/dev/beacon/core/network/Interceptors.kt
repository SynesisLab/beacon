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
        return chain.proceed(
            chain.request().attachInstanceAuth(instanceUrl, bearer, session.settingsSnapshot().customHeaders),
        )
    }
}