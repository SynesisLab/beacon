package dev.beacon.feature.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.beacon.core.render.CardDocument
import dev.beacon.core.render.policy.EmbedPolicy
import dev.beacon.core.render.policy.NavigationDecision
import dev.beacon.ui.theme.LocalBeaconDark
import okhttp3.OkHttpClient
import java.io.File

/**
 * Sandboxed WebView renderer for HTML file artifacts (display_file with
 * mime text/html — previously dumped as raw monospace source). The document
 * is untrusted model/server output, so the pane applies the same §11 origin
 * rules as tool cards ([EmbedPolicy]):
 *
 * - JS + styles ON (artifacts are interactive: mockups, dashboards, charts);
 *   DOM storage OFF — a file artifact keeps no app state;
 * - no file/content access from the page; blocked schemes never load;
 * - http(s) subresources flow through the app's OkHttp client when one is
 *   provided, so instance-origin assets carry the same Authorization rule;
 * - main-frame user navigations open external unless instance-origin;
 *   popups route through the same policy via a throwaway WebView.
 *
 * Theme: the app scheme is injected into the document the same way tool
 * cards do it ([CardDocument.THEME_SEED] flips the `dark` class /
 * `data-theme` / localStorage that web "theme follow" bootstraps read),
 * plus a `color-scheme` meta so UA widgets (scrollbars, form controls)
 * follow too. Fragments (no <head>) get a transparent background so the
 * app's own theme shows through.
 *
 * Gestures: unlike inline cards there is no parent Compose scroll here, so
 * the pane CLAIMS every drag past slop (both axes) — a swipe inside the
 * artifact must never leak to the sidebar drawer's edge-swipe ("menu").
 * Taps below slop still reach links/buttons.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlFileView(
    file: File? = null,
    /** Terminal files arrive as text (no cached file on disk). */
    html: String? = null,
    client: OkHttpClient? = null,
    instanceUrl: String? = null,
) {
    val context = LocalContext.current
    val dark = LocalBeaconDark.current
    var loaded by remember(file, html) { mutableStateOf(false) }
    var loadError by remember(file, html) { mutableStateOf<String?>(null) }

    val raw = remember(file, html) {
        html ?: file?.let { f ->
            runCatching { f.readBytes().toString(Charsets.UTF_8) }.getOrNull()
        }
    }
    if (raw == null && file != null) {
        loadError = "Couldn't read the file."
    }

    // Theme-seeded document: same head injection the card pipeline performs.
    val document = remember(raw, dark) {
        raw?.let { themeDocument(it, dark) }
    }

    val webView = remember {
        HtmlPaneWebView(context).apply {
            // Match the app background while the page paints (no white flash).
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setAllowFileAccessFromFileURLs(false)
            settings.setAllowUniversalAccessFromFileURLs(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // The artifact carries its own theme — the scheme is injected as
            // markup, never let the system invert the raster on top of it.
            settings.isAlgorithmicDarkeningAllowed = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            // Scaleable artifact: pinch to zoom in/out so oversize content can
            // be shrunk to fit the pane (no on-screen +/- buttons).
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            isVerticalScrollBarEnabled = true
        }
    }
    if (dev.beacon.BuildConfig.DEBUG) {
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }
    }

    DisposableEffect(client, instanceUrl) {
        webView.webViewClient = HtmlFileWebViewClient(client, instanceUrl)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean = runCatching {
                val popup = WebView(view.context)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(p: WebView, request: WebResourceRequest): Boolean {
                        if (isUserGesture) {
                            when (EmbedPolicy.navigationDecision(request.url.toString(), true, true, instanceUrl)) {
                                NavigationDecision.OpenExternal -> runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                }
                                else -> Unit
                            }
                        }
                        return true // popups are never adopted into the pane
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).setWebView(popup)
                resultMsg.sendToTarget()
                view.postDelayed({ popup.destroy() }, 1500)
                true
            }.getOrDefault(false)
        }
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (document != null) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (!loaded) {
                        loaded = true
                        // baseUrl null: the artifact is a single self-contained
                        // document; data:/https: subresources still resolve.
                        view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                    }
                },
            )
        } else if (loadError != null) {
            Text(loadError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/**
 * Inject the app scheme into an artifact document — the card pipeline's
 * foreign-doc treatment minus CSP/vendor tags: theme seed first in <head>
 * (fragments: prepended), color-scheme meta + UA-default style, and a
 * transparent page background for headless fragments.
 */
internal fun themeDocument(html: String, dark: Boolean): String {
    val scheme = if (dark) "dark" else "light"
    val head = buildString {
        append(CardDocument.THEME_SEED.format(scheme, dark, dark))
        append("<meta name=\"color-scheme\" content=\"$scheme\">")
        append("<style>html{color-scheme:$scheme;}</style>")
        val fragment = !CardDocument.headOpen.containsMatchIn(html)
        if (fragment) {
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<style>html, body { margin: 0; padding: 8px; background: transparent; }</style>")
        } else if (!html.contains("name=\"viewport\"")) {
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        }
    }
    return CardDocument.injectAfterHead(html, head)
}

/**
 * A pane WebView claims every drag past touch slop (both axes) so swipes
 * inside the artifact never reach the drawer's edge-swipe; taps below slop
 * still activate links and buttons.
 */
class HtmlPaneWebView(context: Context) : WebView(context) {
    private var downX = 0f
    private var downY = 0f
    private var claimed = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                claimed = false
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!claimed) {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dx > slop || dy > slop) {
                        claimed = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (claimed) parent?.requestDisallowInterceptTouchEvent(false)
                claimed = false
                return super.onTouchEvent(event)
            }
            else -> return super.onTouchEvent(event)
        }
    }
}

/**
 * §11 for file artifacts: blocked schemes 404, instance-origin stays
 * in-frame, user-activated external navigations open in the browser.
 * http(s) subresources are proxied through the app client (auth) when
 * one is available; otherwise the WebView fetches them directly.
 */
private class HtmlFileWebViewClient(
    private val client: OkHttpClient?,
    private val instanceUrl: String?,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (EmbedPolicy.navigationDecision(url, request.isForMainFrame, false, instanceUrl)) {
            NavigationDecision.OpenExternal -> {
                runCatching {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                true
            }
            NavigationDecision.LoadInFrame -> false
            NavigationDecision.Block -> true
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (EmbedPolicy.blocked(url)) return emptyResponse()
        val http = client ?: return null // no client → default fetch for http(s)
        if (!url.startsWith("http")) return null
        return runCatching {
            val call = http.newCall(
                okhttp3.Request.Builder().url(url).apply {
                    request.requestHeaders.forEach { (name, value) ->
                        if (name.equals("user-agent", true) || name.equals("accept", true)) {
                            header(name, value)
                        }
                    }
                }.build(),
            )
            val response = call.execute()
            val body = response.body?.byteStream()
            if (!response.isSuccessful || body == null) {
                response.close()
                emptyResponse()
            } else {
                WebResourceResponse(
                    response.header("Content-Type") ?: "application/octet-stream",
                    response.header("Content-Encoding"),
                    body,
                )
            }
        }.getOrNull()
    }

    private fun emptyResponse() =
        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
}
