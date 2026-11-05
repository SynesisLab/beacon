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
    /** Keep the sandbox's localStorage.theme = the app scheme at all times —
     *  the theme probe (3s server wait!) answers with ONE evaluation. */
    private var seededScheme: String? = null

    init {
        scope.launch {
            session.settings.collect {
                val want = if (rendersDark()) "dark" else "light"
                if (want != seededScheme) seedTheme(want)
            }
        }
    }

    private suspend fun seedTheme(scheme: String) = withContext(Dispatchers.Main) {
        ensureSandbox()
        runCatching {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                sandbox?.evaluateJavascript(
                    "try{localStorage.setItem('theme','$scheme')}catch(e){}1",
                ) { _ ->
                    seededScheme = scheme
                    Timber.i("execute sandbox theme=%s", scheme)
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

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
        if (code.isNullOrBlank()) {
            busy.set(false)
            Timber.w("execute call carried no code")
            envelope.reply(buildJsonObject { put("error", "execute call carried no code.") })
            return true
        }
        Timber.i("execute call: code=%s chars head=%s", code.length, code.take(150))
        scope.launch {
            try {
                // The theme is re-seeded inside the expression; one bridge round.
                val raw = withTimeoutOrNull(ANSWER_WAIT_MS) { evaluate(code) }
                Timber.i("execute answer raw=%s", raw?.take(160))
                val payload = when {
                    raw == null -> buildJsonObject { put("error", "execute timed out.") }
                    raw.isBlank() || raw.trim() == "null" -> buildJsonObject { put("error", "execute failed.") }
                    else -> buildJsonObject { put("result", jsResultValue(raw)) }
                }
                Timber.i("execute reply=%s", payload.toString().take(160))
                envelope.reply(payload)
            } catch (t: Throwable) {
                Timber.w(t, "execute failed")
                envelope.reply(buildJsonObject { put("error", t.message ?: "execute failed.") })
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /**
     * evaluateJavascript does NOT await promises — an async code would deliver
     * the promise object itself ("{}"). So: the expression RESOLVES the async
     * result and posts the settled value back through ONE narrow bridge (a
     * single result method — the sandbox keeps no file/network/credential
     * surface; the bridge = returning a string to Kotlin). The theme is
     * re-seeded on every run (about:blank localStorage may not persist).
     */
    private suspend fun evaluate(code: String): String? = withContext(Dispatchers.Main) {
        val view = ensureSandbox()
        val scheme = if (rendersDark()) "dark" else "light"
        val settled = kotlinx.coroutines.CompletableDeferred<String?>()
        val bridge = object {
            @android.webkit.JavascriptInterface
            fun result(json: String) {
                settled.complete(if (json.isNullOrBlank()) "null" else json)
            }
        }
        runCatching { view.addJavascriptInterface(bridge, RESULTS_BRIDGE_NAME) }
        val expression =
            "(function(){try{localStorage.setItem('theme','$scheme')}catch(e){}" +
                "var p=(async () => {" + code + " })();" +
                "p.then(function(v){try{ClientExec.result(JSON.stringify(v))}catch(e){ClientExec.result('null')}},function(e){" +
                "try{ClientExec.result(JSON.stringify({error:String(e)}))}catch(e2){ClientExec.result('null')}});})()"
        runCatching { view.evaluateJavascript(expression) { } }
            .onFailure {
                // script error: nothing will call the bridge
                settled.complete(null)
            }
        withTimeoutOrNull(ANSWER_WAIT_MS) { settled.await() }
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
        /** The tool's probe waits 3s — reply-or-error inside 2.5s. */
        private const val ANSWER_WAIT_MS = 2_500L
        private const val RESULTS_BRIDGE_NAME = "ClientExec"
    }
}
