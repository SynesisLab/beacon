package dev.beacon.core.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.network.socket.ChatSocket
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Engines are per-chat-session; the factory keeps construction Hilt-simple. */
@Singleton
class ChatEngineFactory @Inject constructor(
    private val api: OwuiApi,
    private val store: ChatStore,
    private val json: Json,
    @ApplicationContext private val appContext: Context,
    private val socket: ChatSocket,
) {
    init {
        StreamingLeaseService.ensureChannel(appContext)
    }

    fun create(): ChatEngine = ChatEngine(api, store, json, appContext, socket)
}