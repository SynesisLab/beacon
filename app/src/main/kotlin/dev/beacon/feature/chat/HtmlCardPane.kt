package dev.beacon.feature.chat

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.beacon.core.render.CardDocument
import dev.beacon.core.render.ChatPart
import dev.beacon.core.render.SandboxLevel
import dev.beacon.core.render.policy.EmbedPolicy
import dev.beacon.core.render.policy.NavigationDecision
import dev.beacon.ui.theme.LocalBeaconDark
import okhttp3.OkHttpClient

/**
 * The sandboxed inline WebView pane for [ChatPart.HtmlCard] (agent.md §7.2) —
 * the mobile port of the web's Artifacts/FullHeightIframe pair (M2-2):
 *
 * - one WebView per card; the document is built by [CardDocument.build]
 *   (card's own HTML + beacon bootstrap; vendored mermaid/KaTeX/chart.js from
 *   assets for the Beacon-built/Beacon-detected cases — no CDN there);
 * - base = the vendor asset root so subresources flow through the app's OkHttp
 *   client — whose AuthInterceptor attaches Authorization for the instance
 *   origin ONLY (§11: the WebView shares the rule);
 * - [SandboxLevel] → WebView settings (§7.2: UI = JS+styles; MEDIA/TRUSTED
 *   = server-provenanced embeds also get DOM storage);
 * - bridge = full (height/copy/link) only for Beacon-built docs; model or
 *   server documents get the HEIGHT-ONLY bridge (shim) — no clipboard/link
 *   surface for arbitrary HTML;
 * - links: main-frame user navigations open external unless instance-origin;
 *   popups (window.open/target=_blank) route through [onCreateWindow] policy —
 *   external via the OS chooser, instance targets load back in the card;
 * - height = the shim's reports (MutationObserver + load/resize); dark mode =
 *   the app scheme injected as a body class for Beacon-built docs, with
 *   algorithmic darkening allowed for foreign docs;
 * - "Expand" affordance at the height cap → bottom-sheet full view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlCardPane(
    card: ChatPart.HtmlCard,
    client: OkHttpClient,
    instanceUrl: String?,
    iframeCsp: String? = null,
) {
    val dark = LocalBeaconDark.current
    var heightDp by remember(card.id) { mutableStateOf(card.minHeightDp.dp) }
    var expanded by remember(card.id) { mutableStateOf(false) }
    val density = LocalDensity.current
    // Streaming repaints grow the html; rebuilding per delta keeps previews
    // honest (§7.1). The document remembers on the WHOLE card value.
    val document = remember(card, instanceUrl, iframeCsp, dark) {
        CardDocument.build(card, instanceUrl, iframeCsp, dark)
    }

    if (document != null) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heightDp.coerceAtLeast(card.minHeightDp.dp)),
            ) {
                CardWebView(
                    document = document,
                    client = client,
                    instanceUrl = instanceUrl,
                    sandboxLevel = card.sandboxLevel,
                    onHeight = { px ->
                        heightDp = with(density) { px.toDp().coerceIn(card.minHeightDp.dp, ExpandedCap) }
                    },
                )
            }
            if (heightDp >= ExpandedCap) {
                TextButton(onClick = { expanded = true }) { Text("Expand ⤢") }
            }
        }

        if (expanded) {
            ModalBottomSheet(onDismissRequest = { expanded = false }) {
                Box(Modifier.fillMaxWidth().height(640.dp)) {
                    CardWebView(
                        document = document,
                        client = client,
                        instanceUrl = instanceUrl,
                        sandboxLevel = card.sandboxLevel,
                        onHeight = { },
                    )
                }
            }
        }
    } else {
        HtmlCardPlaceholder(card)
    }
}

private val ExpandedCap = 460.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CardWebView(
    document: CardDocument.Document,
    client: OkHttpClient,
    instanceUrl: String?,
    sandboxLevel: SandboxLevel,
    onHeight: (Float) -> Unit,
) {
    val context = LocalContext.current

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            // §7.2 levels: UI = JS + styles; MEDIA/TRUSTED (server-provenanced
            // embeds) also get DOM storage (app code that keeps state).
            settings.domStorageEnabled = sandboxLevel != SandboxLevel.UI
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setAllowFileAccessFromFileURLs(false)
            settings.setAllowUniversalAccessFromFileURLs(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // Popups reach PopupChromeClient (policy-gated: external for user
            // gestures, instance-origin reloads in-frame, else dead).
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.isAlgorithmicDarkeningAllowed = true
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        }
    }

    DisposableEffect(document.fullBridge, sandboxLevel) {
        // §7.2: ONE bridge NAME, methods by document class — full (height/copy/
        // link) for Beacon-built docs; height-only for model/server documents.
        // Named public classes: the JS bridge requires public methods.
        webView.addJavascriptInterface(
            if (document.fullBridge) {
                FullCardBridge(
                    onHeight = onHeight,
                    onCopy = { text ->
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("beacon", text))
                    },
                    onLink = { url -> mainViewNavigation(url, webView, instanceUrl, context) },
                )
            } else {
                HeightOnlyBridge(onHeight = onHeight)
            },
            "Beacon",
        )
        webView.webChromeClient = PopupChromeClient(instanceUrl) { url ->
            mainViewNavigation(url, webView, instanceUrl, context)
        }
        webView.webViewClient = EmbedWebViewClient(client, instanceUrl, onHeight)
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    // Height events (and plain recompositions) must NOT reload the page —
    // the shim would re-report after every reload, looping forever.
    var renderedKey by remember { mutableStateOf<Any?>(null) }
    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxWidth(),
        update = { view ->
            val key: Any = document.url ?: document.html
            if (renderedKey != key) {
                renderedKey = key
                val url = document.url
                if (url != null) {
                    view.loadUrl(url)
                } else {
                    view.loadDataWithBaseURL(document.baseUrl, document.html, "text/html", "utf-8", null)
                }
            }
        },
    )
}

/** Named public classes so the JS bridge sees visible `@JavascriptInterface` methods. */
class HeightOnlyBridge(private val onHeight: (Float) -> Unit) {
    @JavascriptInterface
    fun height(px: Float) {
        onHeight(px)
    }
}

class FullCardBridge(
    private val onHeight: (Float) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onLink: (String) -> Unit,
) {
    @JavascriptInterface
    fun height(px: Float) {
        onHeight(px)
    }

    @JavascriptInterface
    fun copy(text: String) {
        onCopy(text)
    }

    @JavascriptInterface
    fun link(rawUrl: String) {
        onLink(rawUrl)
    }
}

/** The single §11 origin rule for card navigations (client + bridge + popups). */
private fun openByPolicy(
    rawUrl: String,
    instanceUrl: String?,
    context: Context,
    mainFrameNavigation: ((String) -> Unit)?,
) {
    when (EmbedPolicy.navigationDecision(rawUrl, isUserGesture = true, isPopup = mainFrameNavigation != null, instanceUrl = instanceUrl)) {
        NavigationDecision.OpenExternal -> runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(rawUrl)),
            )
        }
        NavigationDecision.LoadInFrame -> mainFrameNavigation?.invoke(rawUrl)
        NavigationDecision.Block -> Unit
    }
}

private fun mainViewNavigation(url: String, webView: WebView, instanceUrl: String?, context: Context) {
    openByPolicy(url, instanceUrl, context, mainFrameNavigation = { target -> webView.loadUrl(target) })
}

/**
 * `window.open`/`target=_blank` from a card (web: allow-popups → external).
 * A throwaway WebView receives the popup URL; its first navigation decides
 * external-open vs instance-reload, then the throwaway dies. Automatic
 * (gesture-less) popups never leave the card — the §7.2 rule is explicitly
 * for user-activated popups.
 */
private class PopupChromeClient(
    private val instanceUrl: String?,
    private val decide: (String) -> Unit,
) : WebChromeClient() {
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
                    decide(request.url.toString())
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

/** Subresource serving through the ONE app client (auth = same-origin only). */
private class EmbedWebViewClient(
    private val client: OkHttpClient,
    private val instanceUrl: String?,
    /** Kotlin-side height measurement (the shim's CSP-proof fallback). */
    private val onMeasuredHeight: ((Float) -> Unit)? = null,
) : WebViewClient() {
    private val measureJs =
        "(function(){var d=document.scrollingElement||document.documentElement;" +
            "var b=document.body;" +
            "return Math.max(d?d.scrollHeight:0, b?b.scrollHeight:0);})()"

    /**
     * nonce/hash-CSP'd cards (Frontier UI builds its own `script-src 'nonce-…'`
     * policy) block ANY injected inline script — the shim can't run. The host
     * measuring via evaluateJavascript is immune to document CSP: one measure
     * at load + a few early re-measures for async layouts.
     */
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        val measure = {
            runCatching {
                view.evaluateJavascript(measureJs) { value ->
                    val px = value
                        ?.trim()
                        ?.removeSurrounding("\"")
                        ?.toFloatOrNull()
                    if (px != null && px > 0f) onMeasuredHeight?.invoke(px)
                }
            }
        }
        runCatching { measure() }
        if (onMeasuredHeight != null) {
            longArrayOf(400, 900, 1600).forEach { delay ->
                runCatching {
                    view.postDelayed({ runCatching { measure() } }, delay)
                }
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val mainFrame = request.isForMainFrame
        val url = request.url.toString()
        // Main-frame = a user navigation inside the card; subframe (iframe/img)
        // requests are programmatic — the §7.2 rule keys off both.
        val decision = EmbedPolicy.navigationDecision(
            url,
            isUserGesture = mainFrame,
            isPopup = false,
            instanceUrl = instanceUrl,
        )
        return when (decision) {
            NavigationDecision.OpenExternal -> {
                runCatching {
                    view.context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, request.url),
                    )
                }
                true
            }
            NavigationDecision.LoadInFrame, NavigationDecision.Block -> false // frame handles it (blocked ones 404)
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (EmbedPolicy.blocked(url)) {
            return emptyResponse()
        }
        if (!url.startsWith("http")) return null // asset subresources flow through

        return runCatching {
            val call = client.newCall(
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
