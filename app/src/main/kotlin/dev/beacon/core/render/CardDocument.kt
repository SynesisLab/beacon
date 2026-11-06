package dev.beacon.core.render

import dev.beacon.core.render.policy.EmbedPolicy

/**
 * Builds the WebView document for a [ChatPart.HtmlCard]  — the
 * mobile port of the web's two embed surfaces, one code path:
 *
 * - **Foreign documents** ([CardKind.Artifact] fences / raw-blob, [CardKind.Embed]
 *   tool outputs — the "frontier-UI" class): the card's own HTML stays the
 *   document; we only inject the admin `ui.iframe_csp` (web injectCsp parity:
 *   first CSP wins — ours goes right after `<head>`) and the beacon bootstrap
 *   (height reports + optional vendor rendering). Model-authored scripts run
 *   against WebView-per-card settings, not in the bridge document: the bridge
 *   is HEIGHT-ONLY for this class (no copy/link surface for arbitrary HTML).
 * - **Synthetic documents** ([CardKind.Mermaid], [CardKind.Chart]): fully
 *   Beacon-built (escaped diagram text / `</script`-proof JSON config), vendored
 *   libs from assets (`file:///android_asset/vendor/` base — no CDN for our
 *   own markup), full height/copy/link bridge (the content is ours).
 *
 * Height protocol = the web FullHeightIframe contract: the card postMessages
 * or (here) calls `Beacon.height(px)`; we also listen for `iframe:height`
 * style postMessages are unnecessary — same-document bridge covers both.
 * Auto-height via MutationObserver; `iframe:height` postMessage senders
 * (cards written for the web contract) fall through to nothing visible —
 * the shim's own reports cover height.
 *
 * Pure Kotlin — exercised by JVM tests (CardDocumentTest); the JS lives as
 * one constant so every mode shares a single, reviewable runtime.
 */
object CardDocument {

    data class Document(
        /** Web-view base (vendor assets for docs; unused for plain [url] loads). */
        val baseUrl: String,
        val html: String,
        /** null = load [url] as the page instead of rendering [html]. */
        val url: String? = null,
        /** true = full height/copy/link bridge (Beacon-built docs only). */
        val fullBridge: Boolean,
    )

    const val VENDOR_BASE = "file:///android_asset/vendor/"
    private const val MERMAID_TAG = "<script src=\"mermaid/12.1.0/mermaid.min.js\"></script>"
    private const val CHART_TAG = "<script src=\"chartjs/4.6.1/chart.umd.min.js\"></script>"
    private const val KATEX_CSS = "<link rel=\"stylesheet\" href=\"katex/0.19.1/katex.min.css\">"
    private const val KATEX_TAG = "<script src=\"katex/0.19.1/katex.min.js\"></script>"

    /**
     * The card-side theme seed + FORCE (the scheme must win even when storage
     * is unavailable). Values match the exact strings the web's theme readers
     * compare (the Frontier tool's bootstrap: `s.indexOf('dark')>=0` → dark,
     * `s==='light'||s==='her'` → light, else class/matchMedia fallbacks). The
     * direct class flip covers the opaque/file-origin WebView case where
     * localStorage silently won't — the tool's own later applyTheme(det())
     * then re-derives the same value either way.
     */
    internal const val THEME_SEED =
        "<script>(function(){var set=function(){" +
            "var r=document.documentElement;" +
            "try{localStorage.setItem('theme','%s');}catch(e){}" +
            "try{r.classList.toggle('dark',%b);" +
            "r.setAttribute('data-theme',%b?'dark':'light');}catch(e){}};" +
            "set();window.addEventListener('load',set);setTimeout(set,300);})();</script>"

    fun build(
        card: ChatPart.HtmlCard,
        instanceUrl: String?,
        iframeCsp: String?,
        dark: Boolean,
    ): Document? {
        val html = card.html
        if (html != null) {
            return when (card.kind) {
                CardKind.Mermaid -> syntheticDoc(
                    vendorTags = listOf(MERMAID_TAG) + katexTagsFor(html),
                    body = html,
                    dark = dark,
                    interactive = card.interactive,
                )
                CardKind.Chart -> syntheticDoc(
                    vendorTags = listOf(CHART_TAG) + katexTagsFor(html),
                    body = html,
                    dark = dark,
                    interactive = card.interactive,
                )
                CardKind.Artifact, CardKind.Embed -> foreignDoc(
                    html = html,
                    iframeCsp = iframeCsp,
                    dark = dark,
                    interactive = card.interactive,
                )
            }
        }
        val src = card.srcUrl?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val absolute = resolveSrcUrl(src, instanceUrl) ?: return null
        // The pane's WebViewClient drives navigation policy; height for URL
        // cards stays the initial minimum (web: same — cross-origin height
        // needs the card to report it).
        return Document(baseUrl = VENDOR_BASE, html = "", url = absolute, fullBridge = false)
    }

    /** Web FullHeightIframe auto-detect (read as spec): absolute-ish forms only. */
    internal fun resolveSrcUrl(src: String, instanceUrl: String?): String? {
        val t = src.trim()
        if (EmbedPolicy.blocked(t)) return null
        return when {
            t.startsWith("https://") || t.startsWith("http://") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> instanceUrl?.trimEnd('/')?.plus(t)
            else -> null
        }
    }

    /** Vendor tags for a foreign doc — only what the html references. */
    internal fun vendorScriptTagsFor(html: String): List<String> {
        val tags = ArrayList<String>(3)
        if (html.contains("class=\"mermaid\"") ||
            html.contains("language-mermaid") ||
            html.contains("beacon-mermaid")
        ) {
            tags.add(MERMAID_TAG)
        }
        if (html.contains("new Chart(") || html.contains("beacon-chart")) {
            tags.add(CHART_TAG)
        }
        tags.addAll(katexTagsFor(html))
        return tags
    }

    private fun katexTagsFor(html: String): List<String> =
        if (html.contains("beacon-katex") || html.contains("data-katex")) {
            listOf(KATEX_CSS, KATEX_TAG)
        } else {
            emptyList()
        }

    private fun syntheticDoc(vendorTags: List<String>, body: String, dark: Boolean, interactive: Boolean): Document {
        val html = buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            vendorTags.forEach { append(it) }
            append("<style>")
            append("html { color-scheme: light dark; }")
            append("html, body { margin: 0; padding: 8px; font-family: Roboto, 'Segoe UI', sans-serif;")
            if (dark) {
                append(" background: #30302E; color: #F5F4EE;")
            } else {
                append(" background: #FAF9F5; color: #3D3929;")
            }
            append(" }")
            append("pre.mermaid { background: transparent; margin: 0; }")
            append("svg[id^='mermaid-'] { max-width: 100%; height: auto; }")
            append(WIDE_CSS)
            if (!interactive) {
                append("html, body { pointer-events: none; }")
            }
            append("</style></head><body")
            if (dark) {
                append(" class=\"beacon-dark\"")
            }
            append(">")
            append(body)
            append("<script>${BOOTSTRAP}</script></body></html>")
        }
        return Document(VENDOR_BASE, html, fullBridge = true)
    }

    private fun foreignDoc(html: String, iframeCsp: String?, dark: Boolean, interactive: Boolean): Document {
        val fragment = !headOpen.containsMatchIn(html)
        var out = html
        val head = buildString {
            // Theme seed (FIRST element of the document — inline scripts execute
            // in parse order, so nothing is declared/policy-active yet): most
            // tool cards carry a web-iframe "theme follow" bootstrap that reads
            // `parent.localStorage.theme`; in the WebView-per-card there is no
            // parent frame (parent = the card itself), so it falls back to the
            // theme BAKED at generation time — a card generated on a dark
            // session stays dark (white-on-light text) forever. Seeding the
            // card's own localStorage with the app scheme makes that bootstrap
            // follow Beacon's scheme instead — old baked cards included.
            append(THEME_SEED.format(if (dark) "dark" else "light", dark, dark))
            // injectCsp parity (csp.ts:6-12): ours first in <head> → first CSP
            // wins, so the card's own CSP (if any) is effectively overridden —
            // exactly the web's documented behavior.
            if (!iframeCsp.isNullOrBlank()) {
                append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
                append(iframeCsp.trim().replace("\"", "&quot;"))
                append("\">")
            }
            if (fragment) {
                // No markup to restyle: let the app's scheme show through.
                append("<meta charset=\"utf-8\">")
                append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                append("<style>html, body { margin: 0; padding: 8px; background: transparent; }</style>")
                append("<style>$WIDE_CSS</style>")
            } else if (!html.contains("name=\"viewport\"")) {
                append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            }
            if (!fragment) {
                // §11.3b oversize rule for foreign docs too: wide media/blocks
                // scroll in-pane, never shrink-to-fit the layout.
                append("<style>$WIDE_CSS</style>")
            }
            vendorScriptTagsFor(html).forEach { append(it) }
            if (!interactive) {
                append("<style>html, body { pointer-events: none; }</style>")
            }
        }
        if (head.isNotEmpty()) {
            // Fragments (no <head>): the payload PREPENDS; full docs: right
            // after their head tag.
            out = injectAfterHead(out, head)
        }
        out = appendBeforeBody(out, "<script>${BOOTSTRAP}</script>")
        return Document(VENDOR_BASE, out, fullBridge = false)
    }

    internal val headOpen = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
    internal val bodyClose = Regex("</\\s*body\\s*>", RegexOption.IGNORE_CASE)

    /** Right after the opening `<head>` tag (first-wins for CSP metas). */
    internal fun injectAfterHead(html: String, payload: String): String {
        val match = headOpen.find(html) ?: return payload + html
        return html.replaceRange(match.range.first, match.range.last + 1, match.value + payload)
    }

    /** Before `</body>` (kept), else appended (fragment docs). */
    internal fun appendBeforeBody(html: String, payload: String): String {
        val match = bodyClose.find(html) ?: return html + payload
        // Insert-only at the match start; replacing would eat the close tag.
        return html.replaceRange(match.range.first, match.range.first, payload)
    }

    /**
     * §11.3b oversize CSS injected into every card document: wide media and
     * blocks scroll in-pane (main frame included), never shrink-to-fit.
     */
    internal const val WIDE_CSS =
        "img,video,svg{max-width:100%;height:auto}" +
            "pre,table{max-width:100%;overflow-x:auto}" +
            "body{overflow-x:auto}"

    /**
     * One script for every card document: height reports (the shim) + vendor
     * rendering when the libs loaded (synthetics; foreign docs only carry the
     * tags our detection added). Height = the web's `iframe:height` contract,
     * here via the same-document `Beacon.height` bridge. Also reports
     * horizontal overflow (Beacon.wide) and forwards the tool cards'
     * input:prompt:submit (Beacon.prompt) — measurement/prefill channels only.
     */
    internal const val BOOTSTRAP =
        "(function () {" +
            "  function measure() {" +
            "    var d = document.documentElement;" +
            "    var b = document.body;" +
            "    var h = Math.max(d ? d.scrollHeight : 0, b ? b.scrollHeight : 0);" +
            "    var r = b ? b.firstElementChild : null;" +
            "    if (r) { h = Math.max(h, r.scrollHeight, Math.ceil(r.getBoundingClientRect().height)); }" +
            "    return h;" +
            "  }" +
            "  function report() {" +
            "    try {" +
            "      var h = measure();" +
            "      if (window.Beacon && Beacon.height) { Beacon.height(h); }" +
            "      if (window.Beacon && Beacon.wide) { var dd = document.documentElement; Beacon.wide(dd && dd.scrollWidth > dd.clientWidth ? 1 : 0); }" +
            "    } catch (err) {}" +
            "  }" +
            // §11.3b: the card's action chips ask the client to submit a
            // prompt — input:prompt:submit (Chat.svelte:1853). We PREFILL the
            // composer, never auto-send.
            "  window.addEventListener('message', function (e) {" +
            "    try { var m = e.data; if (m && m.type === 'input:prompt:submit' && m.text" +
            "      && window.Beacon && Beacon.prompt) { Beacon.prompt(String(m.text)); } } catch (err) {}" +
            "  });" +
            "  var timer = null;" +
            "  function scheduled() {" +
            "    if (timer) { return; }" +
            "    timer = setTimeout(function () { timer = null; report(); }, 120);" +
            "  }" +
            "  if (window.MutationObserver) {" +
            "    new MutationObserver(scheduled).observe(document.documentElement, { childList: true, subtree: true, attributes: true });" +
            "  }" +
            "  window.addEventListener('resize', report);" +
            "  window.addEventListener('load', report);" +
            "  report();" +
            "  function renderMermaid() {" +
            "    if (!window.mermaid) { return; }" +
            "    mermaid.initialize({ startOnLoad: false," +
            "      theme: document.body.classList.contains('beacon-dark') ? 'dark' : 'default' });" +
            "    mermaid.run({ querySelector: 'pre.mermaid, pre code.language-mermaid, .beacon-mermaid' }).catch(function () {});" +
            "  }" +
            "  function renderChart() {" +
            "    if (!window.Chart) { return; }" +
            "    var cfg = document.getElementById('beacon-chart-config');" +
            "    var canvas = document.querySelector('.beacon-chart canvas');" +
            "    if (cfg && canvas) { new Chart(canvas, JSON.parse(cfg.textContent)); }" +
            "  }" +
            "  function renderKatex() {" +
            "    if (!window.katex) { return; }" +
            "    var nodes = document.querySelectorAll('.beacon-katex');" +
            "    for (var i = 0; i < nodes.length; i++) {" +
            "      var el = nodes[i];" +
            "      var tex = el.getAttribute('data-tex') || el.textContent || '';" +
            "      var display = el.getAttribute('data-block') === 'display';" +
            "      el.textContent = '';" +
            "      katex.render(tex, el, { displayMode: display, throwOnError: false });" +
            "    }" +
            "  }" +
            "  function initVendors() {" +
            "    try { renderMermaid(); } catch (err) {}" +
            "    try { renderChart(); } catch (err) {}" +
            "    try { renderKatex(); } catch (err) {}" +
            "  }" +
            "  if (document.readyState === 'loading') {" +
            "    document.addEventListener('DOMContentLoaded', initVendors);" +
            "  } else {" +
            "    initVendors();" +
            "  }" +
            "})();"
}
