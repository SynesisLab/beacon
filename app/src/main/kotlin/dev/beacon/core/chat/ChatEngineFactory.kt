package dev.beacon.core.chat

import dev.beacon.core.api.ChatsApi
import dev.beacon.core.api.OwuiApi
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Engines are per-chat-session; the factory keeps construction Hilt-simple. */
@Singleton
class ChatEngineFactory @Inject constructor(
    private val api: OwuiApi,
    private val chatsApi: ChatsApi,
    private val store: ChatStore,
    private val json: Json,
) {
    fun create(): ChatEngine = ChatEngine(api, chatsApi, store, json)
}