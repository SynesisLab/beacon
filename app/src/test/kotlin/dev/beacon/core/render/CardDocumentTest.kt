package dev.beacon.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card WebView document builder (§7.2, M2-2): modes, injection points, escaping. */
class CardDocumentTest {

    private fun card(
        kind: CardKind = CardKind.Artifact,
        html: String? = null,
        srcUrl: String? = null,
        level: SandboxLevel = SandboxLevel.UI,
        interactive: Boolean = true,
    ): ChatPart.HtmlCard = ChatPart.HtmlCard(
        id = "t1",
        html = html,
        srcUrl = srcUrl,
        kind = kind,
        sandboxLevel = level,
        interactive = interactive,
    )

    @Test
    fun mermaidDocIsBeaconBuiltWithVendor() {
        val doc = CardDocument.build(card(CardKind.Mermaid, "<pre class=\"mermaid\">A--&gt;B</pre>"), null, null, dark = false)!!
        assertEquals(CardDocument.VENDOR_BASE, doc.baseUrl)
        assertTrue(doc.fullBridge)
        assertTrue(doc.html.contains("mermaid/12.1.0/mermaid.min.js"))
        assertTrue(doc.html.contains("<pre class=\"mermaid\">"))
        assertTrue(doc.html.endsWith("</body></html>"))
        // Light scheme: no dark class, warm paper bg. (The token appears in
        // the bootstrap JS always — assert the class binding, not the token.)
        assertFalse(doc.html.contains("beacon-dark\""))
        assertTrue(doc.html.contains("#FAF9F5"))
    }

    @Test
    fun mermaidAndChartDocsCarryExactlyOneBootstrap() {
        for (kind in listOf(CardKind.Mermaid, CardKind.Chart)) {
            val doc = CardDocument.build(card(kind, "<div>x</div>"), null, null, dark = false)!!
            assertEquals(2, doc.html.split(CardDocument.BOOTSTRAP).size) // once
            assertTrue(doc.html.contains("</script></body></html>"))
        }
    }

    @Test
    fun darkSchemeInjectsClassAndPalette() {
        val doc = CardDocument.build(card(CardKind.Mermaid, "<pre class=\"mermaid\">A</pre>"), null, null, dark = true)!!
        assertTrue(doc.html.contains("<body class=\"beacon-dark\">"))
        assertTrue(doc.html.contains("#30302E"))
    }

    @Test
    fun chartDocCarriesVendorAndConfig() {
        val markup = Segmenter.chartMarkup("{\"type\":\"bar\"}")
        val doc = CardDocument.build(card(CardKind.Chart, markup), null, null, dark = false)!!
        assertTrue(doc.html.contains("chartjs/4.6.1/chart.umd.min.js"))
        assertTrue(doc.html.contains("beacon-chart-config"))
        assertTrue(doc.fullBridge)
    }

    @Test
    fun nonInteractivePreviewBlocksPointerEvents() {
        val doc = CardDocument.build(card(CardKind.Chart, "<div>x</div>", interactive = false), null, null, dark = false)!!
        assertTrue(doc.html.contains("pointer-events: none"))
    }

    @Test
    fun artifactKeepsItsOwnDocument_shimAppendedCspInjected() {
        val html = "<html><head><title>t</title><style>body{}</style></head><body><h1>hi</h1></body></html>"
        val doc = CardDocument.build(card(CardKind.Artifact, html), null, "script-src 'self'", dark = false)!!
        assertFalse(doc.fullBridge) // height-only bridge for model documents
        // No re-wrap: the artifact's own html opens the document.
        assertTrue(doc.html.startsWith("<html"))
        val headAt = doc.html.indexOf("<title")
        val cspAt = doc.html.indexOf("Content-Security-Policy")
        // CSP = FIRST meta in the head (first-wins, csp.ts parity).
        assertTrue(cspAt in 0 until headAt)
        val bodyCloseAt = doc.html.lastIndexOf("</body>")
        val bootAt = doc.html.indexOf(CardDocument.BOOTSTRAP)
        assertTrue(bootAt in 0 until bodyCloseAt)
    }

    @Test
    fun artifactFragmentGetsPrependAndAppend() {
        val html = "<div>hi</div>"
        val doc = CardDocument.build(card(CardKind.Artifact, html), null, null, dark = false)!!
        // No <head>/<body> in the fragment: the payload PREPENDS (theme seed
        // FIRST — parse-position zero), the bootstrap appends — and the
        // background stays transparent so the app's scheme shows through.
        assertTrue(doc.html.startsWith(CardDocument.THEME_SEED.format("light", false, false)))
        assertTrue(doc.html.contains("<meta charset=\"utf-8\">"))
        assertTrue(doc.html.contains("<div>hi</div>"))
        assertTrue(doc.html.endsWith("<script>${CardDocument.BOOTSTRAP}</script>"))
        assertTrue(doc.html.contains("background: transparent"))
        assertFalse(doc.html.contains("#FAF9F5"))
        assertFalse(doc.fullBridge)
    }

    @Test
    fun foreignCardSeedsSchemeBeforeCardOwnThemeFollow() {
        // The Frontier-tool shape: theme baked on <html>, its own "theme
        // follow" bootstrap reading parent.localStorage later in the document.
        val html = "<html lang=\"en\" data-theme=\"dark\" class=\"dark\"><head>" +
            "<style>:root{--fg:#0d0d0d}html.dark{--fg:#ececec}</style></head>" +
            "<body><script>applyTheme(det());</script><div>card</div></body></html>"
        val doc = CardDocument.build(card(CardKind.Embed, html, level = SandboxLevel.MEDIA), null, null, dark = false)!!
        val seedAt = doc.html.indexOf("localStorage.setItem('theme','light')")
        // Parse order decides: the seed (theme = the app scheme) strictly
        // precedes the card's own bootstrap, so applyTheme() adopts it.
        assertTrue(seedAt >= 0)
        assertTrue(seedAt < doc.html.indexOf("applyTheme"))

        // With an injected admin CSP the seed still precedes the policy —
        // nothing is declared at the seed's parse position.
        val cspDoc = CardDocument.build(card(CardKind.Embed, html, level = SandboxLevel.MEDIA), null, "default-src 'self'", dark = false)!!
        assertTrue(cspDoc.html.indexOf("localStorage.setItem('theme','light')") < cspDoc.html.indexOf("Content-Security-Policy"))
    }

    @Test
    fun foreignCardSeedFollowsScheme() {
        val dark = CardDocument.build(card(CardKind.Embed, "<div>x</div>", level = SandboxLevel.MEDIA), null, null, dark = true)!!
        assertTrue(dark.html.contains("localStorage.setItem('theme','dark')"))
        assertTrue(dark.html.contains("classList.toggle('dark',true)"))
        val light = CardDocument.build(card(CardKind.Embed, "<div>x</div>", level = SandboxLevel.MEDIA), null, null, dark = false)!!
        assertTrue(!light.html.contains("localStorage.setItem('theme','dark')"))
        assertTrue(light.html.contains("classList.toggle('dark',false)"))
    }

    @Test
    fun embedDocIsFullBridgeOnlyForSyntheticsAndShimmed() {
        val doc = CardDocument.build(card(CardKind.Embed, "<div>tool ui</div>", level = SandboxLevel.MEDIA), null, null, dark = false)!!
        assertFalse(doc.fullBridge)
        assertTrue(doc.html.contains(CardDocument.BOOTSTRAP))
    }

    @Test
    fun foreignDocVendorsLoadedOnlyWhenReferenced() {
        val chart = CardDocument.build(card(CardKind.Artifact, "<div>new Chart(doc)</div>"), null, null, dark = false)!!
        assertTrue(chart.html.contains("chartjs/4.6.1/chart.umd.min.js"))
        assertFalse(chart.html.contains("mermaid.min.js"))

        val mermaid = CardDocument.build(card(CardKind.Artifact, "<div class=\"mermaid\">x</div>"), null, null, dark = false)!!
        assertTrue(mermaid.html.contains("mermaid/12.1.0/mermaid.min.js"))
        assertFalse(mermaid.html.contains("chart.umd.min.js"))

        val plain = CardDocument.build(card(CardKind.Artifact, "<div>nothing</div>"), null, null, dark = false)!!
        assertFalse(plain.html.contains("mermaid.min.js"))
        assertFalse(plain.html.contains("chart.umd.min.js"))
        assertFalse(plain.html.contains("katex.min.js"))

        val math = CardDocument.build(card(CardKind.Embed, "<span class=\"beacon-katex\">E=mc2</span>"), null, null, dark = false)!!
        assertTrue(math.html.contains("katex/0.19.1/katex.min.js"))
        assertTrue(math.html.contains("katex.min.css"))
    }

    @Test
    fun adminCspEscapesQuotes() {
        val doc = CardDocument.build(card(CardKind.Artifact, "<div>x</div>"), null, "frame-ancestors \"none\"", dark = false)!!
        assertTrue(doc.html.contains("content=\"frame-ancestors &quot;none&quot;\""))
    }

    @Test
    fun syntheticsIgnoreAdminCsp() {
        val doc = CardDocument.build(card(CardKind.Mermaid, "<pre class=\"mermaid\">A</pre>"), null, "default-src 'none'", dark = false)!!
        assertFalse(doc.html.contains("Content-Security-Policy"))
    }

    @Test
    fun srcUrlResolutionTable() {
        assertEquals("https://a.example/b", CardDocument.resolveSrcUrl("https://a.example/b", "https://inst.example"))
        assertEquals("https://c.example/x", CardDocument.resolveSrcUrl("//c.example/x", null))
        assertEquals("https://inst.example/api/x", CardDocument.resolveSrcUrl("/api/x", "https://inst.example"))
        assertNull(CardDocument.resolveSrcUrl("api/x", "https://inst.example"))
        assertNull(CardDocument.resolveSrcUrl("/api/x", null))
        assertNull(CardDocument.resolveSrcUrl("file:///etc/passwd", "https://inst.example"))
    }

    @Test
    fun srcUrlCardIsNullWhenUnresolvable() {
        assertNull(CardDocument.build(card(srcUrl = "api/x", html = null), "https://inst.example", null, dark = false))
        val doc = CardDocument.build(card(srcUrl = "/api/v1/f/1", html = null), "https://inst.example", null, dark = false)!!
        assertEquals("https://inst.example/api/v1/f/1", doc.url)
        assertEquals("", doc.html)
        assertFalse(doc.fullBridge)
    }

    @Test
    fun bootstrapNeverClosesItsOwnScriptTag() {
        assertFalse(CardDocument.BOOTSTRAP.contains("</"))
        // The height protocol: the same-document Beacon bridge (web's
        // iframe:height contract on the mobile side).
        assertTrue(CardDocument.BOOTSTRAP.contains("Beacon.height"))
    }
}
