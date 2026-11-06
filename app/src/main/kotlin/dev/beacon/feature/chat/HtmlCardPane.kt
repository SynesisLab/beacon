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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.beacon.core.render.CardDocument
import dev.beacon.core.render.ChatPart
import dev.beacon.core.render.SandboxLevel
import dev.beacon.core.render.policy.EmbedPolicy
import dev.beacon.core.render.policy.NavigationDecision
import dev.beacon.ui.theme.LocalBeaconDark
import okhttp3.OkHttpClient
import timber.log.Timber

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
    /** Card chip → composer prefill (input:prompt:submit; prefill only). */
    onPrompt: (String) -> Unit = {},
) {
    val dark = LocalBeaconDark.current
    var heightDp by remember(card.id) { mutableStateOf(card.minHeightDp.dp) }
    var expanded by remember(card.id) { mutableStateOf(false) }
    // §11.3b: the document overflows horizontally → edge-fade + hint row.
    var wide by remember(card.id) { mutableStateOf(false) }
    // Streaming repaints grow the html; rebuilding per delta keeps previews
    // honest (§7.1). The document remembers on the WHOLE card value.
    val document = remember(card, instanceUrl, iframeCsp, dark) {
        CardDocument.build(card, instanceUrl, iframeCsp, dark)
    }

    if (document != null) {
        // Hoisted OUT of the draw lambda (MaterialTheme is @Composable).
        val fadeColor = MaterialTheme.colorScheme.surface
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heightDp.coerceAtLeast(card.minHeightDp.dp))
                    // Web parity: the card sits as a rounded block in the flow
                    // (FullHeightIframe's rounded-2xl) — the clip shows the app
                    // background at the corners, no hard rectangle seam.
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    // Oversize edge-fade mask (mockup 02/12: the ↔ scroll fades
                    // at both edges instead of a hard clip).
                    .then(
                        if (wide) {
                            Modifier.drawWithContent {
                                drawContent()
                                val w = size.width
                                val fade = 20.dp.toPx()
                                val gradL = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(fadeColor.copy(alpha = 0.9f), fadeColor.copy(alpha = 0f)),
                                    0f,
                                    fade,
                                )
                                val gradR = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(fadeColor.copy(alpha = 0f), fadeColor.copy(alpha = 0.9f)),
                                    w - fade,
                                    w,
                                )
                                drawRect(gradL)
                                drawRect(gradR)
                            }
                        } else Modifier,
                    ),
            ) {
                CardWebView(
                    document = document,
                    client = client,
                    instanceUrl = instanceUrl,
                    sandboxLevel = card.sandboxLevel,
                    onHeight = { px ->
                        // The measure/bridge report CSS pixels; the WebView's
                        // CSS scale (devicePixelRatio) equals the app density,
                        // so a CSS px height IS the dp height — Float.toDp()
                        // would divide a second time (the 2026-11-05 clip:
                        // 1111css content → 456dp box, one viewport tall).
                        heightDp = px.dp.coerceIn(card.minHeightDp.dp, ExpandedCap)
                    },
                    onWide = { w -> wide = w == 1 },
                    // The prompt channel is for server-provenanced tool cards
                    // only (Embed) — never raw model HTML (ADR-0013).
                    onPrompt = if (card.kind == dev.beacon.core.render.CardKind.Embed) onPrompt else null,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (wide) {
                    Text(
                        "↔ oversize: scrolls, never shrinks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (heightDp >= ExpandedCap) {
                    TextButton(onClick = { expanded = true }) { Text("Expand ⤢") }
                }
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
                        onWide = { },
                        onPrompt = null,
                    )
                }
            }
        }
    } else {
        HtmlCardPlaceholder(card)
    }
}

private val ExpandedCap = 4000.dp

/**
 * The seamlessness rule (the web's iframe in the page): vertical scrolls over
 * a card belong to the CHAT list — the WebView hands them to the Compose
 * scroll instead of eating them. Taps, links and horizontal gestures
 * (maps/galleries/wide tables) stay native.
 *
 * The gesture axis is LOCKED at the first slop crossing (user report
 * 2026-11-06: sideways card scrolling was "not smooth" and "extending the
 * menu" — the old per-move |dy| > |dx| test flip-flopped mid-drag, stuttering
 * the scroll and leaking the gesture to the sidebar's edge-swipe). A locked
 * HORIZONTAL drag claims the whole parent chain
 * ([android.view.ViewParent.requestDisallowInterceptTouchEvent] crosses the
 * Compose interop and cancels in-progress Compose gestures — the drawer
 * cannot open off a card's sideways scroll); a locked VERTICAL drag hands
 * off to the chat list as before.
 */
class BeaconCardWebView(context: Context) : WebView(context) {
    init {
        // §11/§7.2 seamlessness: the pre-paint surface = the app background, so
        // a chat switch never flashes the WebView's default white first paint.
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private companion object {
        private const val AXIS_NONE = 0
        private const val AXIS_VERTICAL = 1
        private const val AXIS_HORIZONTAL = 2
    }

    private var downX = 0f
    private var downY = 0f
    private var axis = AXIS_NONE
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                axis = AXIS_NONE
                // let inner content (buttons, links) claim the stream first
                return super.onTouchEvent(event)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (axis == AXIS_NONE) {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    // Below the slop the stream stays untouched (taps/links).
                    if (dx <= slop && dy <= slop) {
                        return super.onTouchEvent(event)
                    }
                    // The first crossing LOCKS the axis for the whole gesture —
                    // no per-move flip-flop (the stutter) and no mid-drag handoff.
                    axis = if (dy > dx) AXIS_VERTICAL else AXIS_HORIZONTAL
                    if (axis == AXIS_HORIZONTAL) {
                        // §11.3b: the wide card OWNS sideways drags. Block every
                        // parent interception for the rest of the stream — the
                        // request crosses the Compose interop and cancels
                        // in-progress Compose gestures, so the sidebar's
                        // left-edge swipe can never open off a horizontal
                        // card scroll.
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                return when (axis) {
                    AXIS_VERTICAL -> false // locked dominant-vertical → the chat list
                    else -> super.onTouchEvent(event)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                axis = AXIS_NONE
                // Release the claim so the next gesture (drawer swipe, chat
                // scroll) works normally again.
                parent?.requestDisallowInterceptTouchEvent(false)
                return super.onTouchEvent(event)
            }
            else -> return super.onTouchEvent(event)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CardWebView(
    document: CardDocument.Document,
    client: OkHttpClient,
    instanceUrl: String?,
    sandboxLevel: SandboxLevel,
    onHeight: (Float) -> Unit,
    onWide: (Int) -> Unit = {},
    onPrompt: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current

    val webView = remember {
        BeaconCardWebView(context).apply {
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
            // Force Dark OFF: the card carries its OWN theme (the seed +
            // baked data-theme). System-dark + light app made the WebView's
            // algorithmic darkening INVERT light content at paint — DOM
            // computed light, raster dark (the 2026-11-05 mismatch).
            settings.isAlgorithmicDarkeningAllowed = false
            // Popups reach PopupChromeClient (policy-gated: external for user
            // gestures, instance-origin reloads in-frame, else dead).
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            // Web parity: the card = a full-height rectangle in the flow; the
            // CHAT list scrolls, the card doesn't (vertical bar off; the
            // subclass hands vertical drags to the Compose scroll).
            isVerticalScrollBarEnabled = false
            // NO LAYER_TYPE_HARDWARE here: WebView is already hardware-composited
            // and an extra view HardwareLayer can freeze its surface against
            // attribute-driven restyles (the 2026-11-05 dark-raster stale frame).
        }
    }
    // Debug builds only: inspectable card WebViews (chrome://inspect).
    if (dev.beacon.BuildConfig.DEBUG) {
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }
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
                    onWide = onWide,
                    onPrompt = onPrompt,
                )
            } else {
                HeightOnlyBridge(onHeight = onHeight, onWide = onWide, onPrompt = onPrompt)
            },
            "Beacon",
        )
        webView.webChromeClient = PopupChromeClient(instanceUrl) { url ->
            mainViewNavigation(url, webView, instanceUrl, context)
        }
        webView.webViewClient = EmbedWebViewClient(client, instanceUrl, onHeight, onWide)
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
class HeightOnlyBridge(
    private val onHeight: (Float) -> Unit,
    private val onWide: ((Int) -> Unit)? = null,
    private val onPrompt: ((String) -> Unit)? = null,
) {
    @JavascriptInterface
    fun height(px: Float) {
        onHeight(px)
    }

    /** §11.3b: 1 when the document overflows horizontally (oversize scroll). */
    @JavascriptInterface
    fun wide(w: Int) {
        onWide?.invoke(w)
    }

    /** input:prompt:submit from a tool card → composer PREFILL (never sends). */
    @JavascriptInterface
    fun prompt(text: String) {
        onPrompt?.invoke(text)
    }
}

class FullCardBridge(
    private val onHeight: (Float) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onLink: (String) -> Unit,
    private val onWide: ((Int) -> Unit)? = null,
    private val onPrompt: ((String) -> Unit)? = null,
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

    @JavascriptInterface
    fun wide(w: Int) {
        onWide?.invoke(w)
    }

    @JavascriptInterface
    fun prompt(text: String) {
        onPrompt?.invoke(text)
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
    /** CSP-proof fallback for the shim's Beacon.wide report (oversize scroll). */
    private val onMeasuredWide: ((Int) -> Unit)? = null,
) : WebViewClient() {
    private val measureJs =
        "(function(){var d=document.scrollingElement||document.documentElement;" +
            "var b=document.body;" +
            "var h=Math.max(d?d.scrollHeight:0, b?b.scrollHeight:0);" +
            "var r=b?b.firstElementChild:null;" +
            "if(r){h=Math.max(h, r.scrollHeight, Math.ceil(r.getBoundingClientRect().height));}" +
            "return h;})()"
    private val wideJs =
        "(function(){var d=document.documentElement;return (d && d.scrollWidth > d.clientWidth) ? 1 : 0;})()"

    // Stability loop state: nonce-CSP'd cards (Frontier) can't run the injected
    // height shim, and their content lands LATE (staggered block animations +
    // live-data re-renders) — a fixed 400/900/1600ms ladder measured too early
    // and the WebView kept the stale (short) height, clipping the card. Keep
    // measuring past load until the height holds across two rounds (~10s cap).
    private var round = 0
    private var prevHeight = 0f
    private var stableRounds = 0

    /**
     * nonce/hash-CSP'd cards (Frontier UI builds its own `script-src 'nonce-…'`
     * policy) block ANY injected inline script — the shim can't run. The host
     * measuring via evaluateJavascript is immune to document CSP.
     */
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        // Debug probe: the card's live theme state (storage + forced palette)
        // — one line per card page, enough to diagnose theme-bake mismatches.
        runCatching {
            view.evaluateJavascript(
                "(function(){try{return localStorage.getItem('theme')+' | '+document.documentElement.className" +
                    "+' | '+document.documentElement.getAttribute('data-theme');}catch(e){return 'ERR '+String(e);}})()",
            ) { v -> Timber.i("card theme state: %s", v) }
        }
        if (onMeasuredHeight == null) return
        round = 0
        prevHeight = 0f
        stableRounds = 0
        measureLoop(view)
    }

    private fun measureLoop(view: WebView) {
        if (round++ > MEASURE_MAX_ROUNDS) return
        runCatching {
            view.evaluateJavascript(measureJs) { value ->
                val px = value?.trim()?.removeSurrounding("\"")?.toFloatOrNull()
                if (px != null && px > 0f) onMeasuredHeight?.invoke(px)
                if (px == prevHeight) stableRounds += 1 else stableRounds = 0
                prevHeight = px ?: -1f
                if (px != null && stableRounds < 2) {
                    runCatching { view.postDelayed({ measureLoop(view) }, MEASURE_INTERVAL_MS) }
                }
            }
            onMeasuredWide?.let { cb ->
                view.evaluateJavascript(wideJs) { v ->
                    v?.trim()?.removeSurrounding("\"")?.toIntOrNull()?.let(cb)
                }
            }
        }
    }

    private companion object {
        const val MEASURE_MAX_ROUNDS = 24
        const val MEASURE_INTERVAL_MS = 350L
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
