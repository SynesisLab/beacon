package dev.beacon.core.network.socket

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.beacon.core.data.SessionStore
import dev.beacon.core.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The `execute` event_call, answered (socket/main.py:1786-1888; the web's own
 * page-scope evaluation at Chat.svelte:1755-1766). Tools that probe the ACTIVE
 * client — Frontier UI's theme read (`_owui_theme`: localStorage `theme` →
 * documentElement class → prefers-color-scheme) — otherwise fell back to DARK
 * and baked dark cards into a light app (the 2026-11-03 report).
 *
 * Sandboxing: the code runs in ONE app-owned, never-displayed WebView (about:
 * blank) with NO JS bridge, NO file/content access and no interceptor — and
 * seeded with the app's CURRENT scheme in localStorage, so probes that ask the
 * client for its theme get what Beacon actually renders (forced Light/Dark
 * beats prefers-color-scheme). A tool asking for anything else still runs
 * sandboxed: no credentials reach it (the cookie/credential surfaces the
 * cards' client uses do not exist here), and results must be JSON-able.
 */
@Singleton
class ExecuteSandbox @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionStore,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val jsonParser = Json { ignoreUnknownKeys = true }
    private var sandbox: WebView? = null
    private val busy = AtomicBoolean(false)

    /** True when this envelope type = execute; answers it asynchronously. */
    fun tryAnswer(envelope: SocketEnvelope): Boolean {
        if ((envelope.data["type"] as? JsonPrimitive)?.content != EXECUTE_TYPE) return false
        if (!envelope.canReply()) return false
        if (!busy.compareAndSet(false, true)) {
            // One at a time (the single sandbox): a second concurrent call
            // answers cancelled — the server times it out either way.
            envelope.reply(buildJsonObject { put("error", "Beacon is already executing a request.") })
            return true
        }
        val code = ((envelope.data["data"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content
        scope.launch {
            try {
                if (code.isNullOrBlank()) {
                    envelope.reply(buildJsonObject { put("error", "execute call carried no code.") })
                } else {
                    val raw = withTimeoutOrNull(TIMEOUT_MS) { evaluate(code) }
                    val payload = when {
                        raw == null -> buildJsonObject { put("error", "execute timed out.") }
                        raw.isBlank() || raw.trim() == "null" -> buildJsonObject { put("error", "execute failed.") }
                        else -> buildJsonObject { put("result", jsResultValue(raw)) }
                    }
                    envelope.reply(payload)
                }
            } catch (t: Throwable) {
                envelope.reply(buildJsonObject { put("error", t.message ?: "execute failed.") })
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /** The seed + run: (1) localStorage.theme = the app scheme now; (2) eval. */
    private suspend fun evaluate(code: String): String? = withContext(Dispatchers.Main) {
        val view = ensureSandbox()
        val scheme = if (rendersDark()) "dark" else "light"
        suspendCancellableCoroutine { cont ->
            val seedAndRun: () -> Unit = {
                runCatching {
                    view.evaluateJavascript(
                        "try{localStorage.setItem('theme','$scheme')}catch(e){}1",
                    ) { _ ->
                        runCatching {
                            view.evaluateJavascript(EXPRESSION_OPEN + code + EXPRESSION_CLOSE) { result ->
                                cont.resume(result)
                            }
                        }.onFailure { cont.resume(null) }
                    }
                }.onFailure { cont.resume(null) }
            }
            if (view.url == "about:blank") seedAndRun() else {
                view.post { seedAndRun() }
            }
        }
    }

    @MainThread
    private fun ensureSandbox(): WebView =
        sandbox ?: WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // the probes read localStorage
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setAllowFileAccessFromFileURLs(false)
            settings.setAllowUniversalAccessFromFileURLs(false)
            settings.mediaPlaybackRequiresUserGesture = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            loadUrl("about:blank")
            sandbox = this
        }.also { Timber.d("execute sandbox ready") }

    /** JS literal → kotlinx JsonElement (typed booleans/numbers preserved). */
    private fun jsResultValue(raw: String): JsonElement {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return JsonNull
        return runCatching { jsonParser.decodeFromString<JsonElement>(trimmed) }.getOrElse {
            when {
                trimmed.toDoubleOrNull() != null -> JsonPrimitive(trimmed.toDouble())
                trimmed == "true" -> JsonPrimitive(true)
                trimmed == "false" -> JsonPrimitive(false)
                else -> JsonPrimitive(trimmed)
            }
        }
    }

    /** What Beacon renders NOW (forced modes beat the system flag). */
    private fun rendersDark(): Boolean = when (session.settingsSnapshot().themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    fun shutdown() {
        scope.cancel()
        runCatching {
            (sandbox ?: return).destroy()
        }
        sandbox = null
    }

    companion object {
        const val EXECUTE_TYPE = "execute"
        private const val TIMEOUT_MS = 10_000L
        private const val EXPRESSION_OPEN = "(async () => { "
        private const val EXPRESSION_CLOSE = " })()"
    }
}
