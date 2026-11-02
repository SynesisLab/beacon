package dev.beacon.core.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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

    /** Hot snapshot so sync call sites (interceptors) never block on DataStore IO. */
    private val snapshot: StateFlow<ServerSettings> =
        dataStore.data.stateIn(scope, SharingStarted.Eagerly, ServerSettings())

    fun settingsSnapshot(): ServerSettings = snapshot.value

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
        update { it.copy(authMethod = method) }
    }

    suspend fun signOut() {
        secure.deleteAll()
        update { ServerSettings() }
    }
}