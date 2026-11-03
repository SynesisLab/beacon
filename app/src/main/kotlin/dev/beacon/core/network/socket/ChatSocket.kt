package dev.beacon.core.network.socket

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.beacon.core.data.SessionStore
import dev.beacon.core.data.sameOrigin
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The chat engine's view of the OWUI socket (M4-d3). A seam so the engine
 * stays constructible on the JVM (tests pass null); the device binding hooks
 * the singleton [OwuiSocket] with the session's origin/token.
 */
interface ChatSocket {
    /** Connects using the current session's origin/token; true → session_id rides the request. */
    suspend fun ensureConnected(timeoutMs: Long = 5_000): Boolean

    /** The live socket session id to send in the request body (Chat.svelte:3281). */
    fun sessionId(): String?

    /** All server events; envelopes {chat_id, message_id, data}. */
    val events: SharedFlow<SocketEnvelope>

    /** Chat-room presence (the shared throttled-output stream lives in room chat:{id}). */
    fun joinChat(chatId: String?): Boolean = false
    fun leaveChat(chatId: String?): Boolean = false
}

@Singleton
class HiltChatSocket @Inject constructor(
    private val session: SessionStore,
    private val socketLazy: Lazy<OwuiSocket>,
) : ChatSocket {
    override suspend fun ensureConnected(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val url = session.instanceUrlSnapshot() ?: return@withContext false
        val token = tokenFor(url) ?: return@withContext false
        socketLazy.get().ensureConnected(url, token, timeoutMs)
    }

    override fun sessionId(): String? = socketLazy.get().sessionId

    override val events: SharedFlow<SocketEnvelope> get() = socketLazy.get().events

    override fun joinChat(chatId: String?): Boolean = socketLazy.get().joinChat(chatId)

    override fun leaveChat(chatId: String?): Boolean = socketLazy.get().leaveChat(chatId)

    private fun tokenFor(url: String): String? {
        val settings = session.settingsSnapshot()
        if (settings.serverUrl.isNotBlank() && !sameOrigin(settings.serverUrl, url)) return null
        return session.credentialFor(url)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatSocketModule {
    @Binds
    abstract fun bindChatSocket(impl: HiltChatSocket): ChatSocket
}