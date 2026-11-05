package dev.beacon.core.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * True when both URLs are the same scheme+host+port origin (agent.md §11).
 * Pure java.net.URI on purpose — this rule is exercised by plain-JVM tests.
 * Malformed/unresolvable inputs compare as NOT same-origin.
 */
fun sameOrigin(a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank()) return false
    val parsedA = a.toOriginParts() ?: return false
    val parsedB = b.toOriginParts() ?: return false
    return parsedA.scheme == parsedB.scheme &&
        parsedA.host == parsedB.host &&
        parsedA.port == parsedB.port
}

private data class OriginParts(val scheme: String, val host: String, val port: Int)

private fun String.toOriginParts(): OriginParts? = try {
    val uri = URI(this)
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    OriginParts(scheme, host, uri.port.takeIf { it != -1 } ?: defaultPort(scheme))
} catch (_: Exception) {
    null
}

private fun defaultPort(scheme: String?): Int = when (scheme?.lowercase()) {
    "https" -> 443
    "http" -> 80
    else -> -1
}

/**
 * Device-side session: server settings (DataStore, no secrets) + credentials
 * (SecureStore). The interceptor and screens read credentials only through here.
 */
@Singleton
class SessionStore @Inject constructor(
    private val dataStore: DataStore<ServerSettings>,
    private val secure: SecureStore,
) : SettingsFlow {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val settings: Flow<ServerSettings> = dataStore.data

    /**
     * M4: a same-origin AUTHENTICATED call came back 401 — the stored JWT/API
     * key is dead (2026-11-03 device log: every authed GET 401'd while
     * /api/config 200'd, and the UI silently went empty). The interceptor
     * raises this; the chat surface shows the re-auth banner. New credentials
     * or a sign-out clear it.
     */
    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired.asStateFlow()

    fun markAuthExpired() { _authExpired.value = true }
    fun clearAuthExpired() { _authExpired.value = false }

    /** Hot snapshot so sync call sites (interceptors) never block on DataStore IO. */
    private val snapshot: StateFlow<ServerSettings> =
        dataStore.data.stateIn(scope, SharingStarted.Eagerly, ServerSettings())

    fun settingsSnapshot(): ServerSettings = snapshot.value

    /**
     * M4 headers editor: custom headers are credentials (gateway keys), so the
     * blob lives in SecureStore — not the settings DataStore (§11). Loaded once
     * at construction (off-main), updated on [setCustomHeaders], wiped by
     * [signOut]; interceptors read the hot snapshot, never the disk.
     */
    private val _customHeaders = MutableStateFlow<Map<String, String>>(emptyMap())
    val customHeaders: StateFlow<Map<String, String>> = _customHeaders.asStateFlow()

    init {
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching {
                    CustomHeaders.decode(secure.read(SecureStore.HEADERS_ALIAS))
                }.getOrDefault(emptyMap())
            }
            _customHeaders.value = stored
        }
    }

    fun customHeadersSnapshot(): Map<String, String> = _customHeaders.value

    /** Editor write path: re-writes the whole encrypted blob (validated upstream). */
    suspend fun setCustomHeaders(map: Map<String, String>) {
        val sanitized = CustomHeaders.sanitize(map)
        withContext(Dispatchers.IO) {
            if (sanitized.isEmpty()) {
                secure.delete(SecureStore.HEADERS_ALIAS)
            } else {
                secure.store(SecureStore.HEADERS_ALIAS, CustomHeaders.encode(sanitized))
            }
        }
        _customHeaders.value = sanitized
    }

    fun instanceUrlSnapshot(): String? = snapshot.value.serverUrl.takeIf { it.isNotBlank() }

    suspend fun current(): ServerSettings = dataStore.data.first()

    suspend fun update(transform: (ServerSettings) -> ServerSettings) {
        dataStore.updateData(transform)
    }

    /** Bearer token for the given instance origin, or null when unauthenticated. */
    fun credentialFor(serverUrl: String): String? {
        val settings = snapshot.value
        if (!sameOrigin(settings.serverUrl, serverUrl)) return null
        return when (settings.authMethod) {
            AuthMethod.ApiKey -> secure.read(SecureStore.API_KEY_ALIAS)
            AuthMethod.Password -> secure.read(SecureStore.AUTH_TOKEN_ALIAS)
            AuthMethod.None -> null
        }
    }

    suspend fun adoptCredentials(method: AuthMethod, secret: String) {
        val alias = when (method) {
            AuthMethod.ApiKey -> SecureStore.API_KEY_ALIAS
            AuthMethod.Password -> SecureStore.AUTH_TOKEN_ALIAS
            AuthMethod.None -> return
        }
        secure.store(alias, secret)
        clearAuthExpired()
        update { it.copy(authMethod = method) }
    }

    suspend fun signOut() {
        secure.deleteAll()
        clearAuthExpired()
        _customHeaders.value = emptyMap()
        update { ServerSettings() }
    }
}