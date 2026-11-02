package dev.beacon.feature.chat

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.beacon.core.render.ChatPart
import dev.beacon.core.render.policy.NavigationDecision
import dev.beacon.core.render.policy.EmbedPolicy
import okhttp3.OkHttpClient

/**
 * The sandboxed inline WebView pane for [ChatPart.HtmlCard] (agent.md §7.2):
 *
 * - loads our bundled shell (mermaid/KaTeX/chart.js from assets — no CDN);
 * - base = the shell's asset URL, so network subresources are intercepted and
 *   served through the app's OkHttp client — whose AuthInterceptor attaches
 *   Authorization for the instance origin ONLY (§11: the WebView shares the rule);
 * - blocked schemes (file/content/intent/data/javascript) never load;
 * - one origin-checked JS bridge: height/copy/link (links → policy → external);
 * - height = intrinsic scrollHeight via bridge + MutationObserver, grows live;
 * - dark mode: prefers-color-scheme in the shell + algorithmic darkening allowed;
 * - "Expand" affordance at the height cap → full-screen modal (artifact style).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlCardPane(
    card: ChatPart.HtmlCard,
    client: OkHttpClient,
    instanceUrl: String?,
) {
    var heightDp by remember(card.id) { mutableStateOf(card.minHeightDp.dp) }
    var expanded by remember(card.id) { mutableStateOf(false) }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(heightDp.coerceAtLeast(card.minHeightDp.dp)),
        ) {
            CardWebView(
                card = card,
                client = client,
                instanceUrl = instanceUrl,
                onHeight = { px -> heightDp = (px / 2.5).dp.coerceIn(60.dp, ExpandedCap) },
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
                    card = card,
                    client = client,
                    instanceUrl = instanceUrl,
                    onHeight = { },
                )
            }
        }
    }
}

private val ExpandedCap = 460.dp

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CardWebView(
    card: ChatPart.HtmlCard,
    client: OkHttpClient,
    instanceUrl: String?,
    onHeight: (Float) -> Unit,
) {
    val context = LocalContext.current
    val shell = remember { loadShell(context) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setAllowFileAccessFromFileURLs(false)
            settings.setAllowUniversalAccessFromFileURLs(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.isAlgorithmicDarkeningAllowed = true
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        }
    }

    DisposableEffect(card.id) {
        val bridge = object {
            @JavascriptInterface
            fun height(px: Float) {
                onHeight(px)
            }

            @JavascriptInterface
            fun copy(text: String) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("beacon", text))
            }

            @JavascriptInterface
            fun link(rawUrl: String) {
                val decision = EmbedPolicy.navigationDecision(
                    rawUrl,
                    isUserGesture = true,
                    isPopup = true,
                    instanceUrl = instanceUrl,
                )
                if (decision == NavigationDecision.OpenExternal) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(rawUrl)),
                        )
                    }
                }
            }
        }
        webView.addJavascriptInterface(bridge, "Beacon")
        webView.webViewClient = EmbedWebViewClient(client)
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxWidth(),
        update = { view ->
            val html = shell.replace("__BEACON_CARD_HTML__", card.html ?: "")
            view.loadDataWithBaseURL("file:///android_asset/vendor/", html, "text/html", "utf-8", null)
        },
    )
}

private fun loadShell(context: Context): String = try {
    context.assets.open("vendor/shell.html").bufferedReader().use { it.readText() }
} catch (_: Exception) {
    "<html><body>card shell missing</body></html>"
}

/** Subresource serving through the ONE app client (auth = same-origin only). */
private class EmbedWebViewClient(
    private val client: OkHttpClient,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        EmbedPolicy.navigationDecision(
            request.url.toString(),
            isUserGesture = request.isForMainFrame && true,
            isPopup = false,
            instanceUrl = null,
        ) == NavigationDecision.OpenExternal

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