package dev.beacon.core.render.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** agent.md §7.2/§11 policy rules — pure tests (the WebView client is a thin shell). */
class EmbedPolicyTest {
    @Test
    fun sourceDetectsUrlVsHtml() {
        assertTrue(EmbedPolicy.source("https://widgets.example/x.html") is EmbedPolicy.Source.Url)
        assertTrue(EmbedPolicy.source("//cdn.example/x") is EmbedPolicy.Source.Url)
        assertTrue(EmbedPolicy.source("/api/v1/files/abc/content") is EmbedPolicy.Source.Url)
        assertTrue(EmbedPolicy.source("<div>hello</div>") is EmbedPolicy.Source.Html)
    }

    @Test
    fun blockedSchemesNeverLoad() {
        listOf(
            "file:///etc/passwd",
            "content://contacts/1",
            "intent://open#Intent;…",
            "javascript:alert(1)",
            "data:text/html,script",
        ).forEach { url ->
            assertEquals("blocked: $url", NavigationDecision.Block, EmbedPolicy.navigationDecision(url, true, false, "https://ai.example.com"))
            assertTrue(EmbedPolicy.blocked(url))
        }
    }

    @Test
    fun instanceOriginStaysInFrame() {
        val inst = "https://ai.example.com"
        assertEquals(
            NavigationDecision.LoadInFrame,
            EmbedPolicy.navigationDecision("https://ai.example.com/x", isUserGesture = true, isPopup = false, instanceUrl = inst),
        )
    }

    @Test
    fun userLinksAndPopupsGoExternal() {
        val inst = "https://ai.example.com"
        assertEquals(
            NavigationDecision.OpenExternal,
            EmbedPolicy.navigationDecision("https://evil.example/x", isUserGesture = true, isPopup = false, instanceUrl = inst),
        )
        assertEquals(
            NavigationDecision.OpenExternal,
            EmbedPolicy.navigationDecision("https://evil.example/x", isUserGesture = false, isPopup = true, instanceUrl = inst),
        )
    }

    @Test
    fun automaticNonUserLoadsInFrame() {
        assertEquals(
            NavigationDecision.LoadInFrame,
            EmbedPolicy.navigationDecision("https://cdn.example/font.css", isUserGesture = false, isPopup = false, instanceUrl = null),
        )
    }

    @Test
    fun levelElevationRequiresProvenance() {
        assertEquals(EmbedPolicy.Level.UI, EmbedPolicy.levelFor("<div>x</div>", provenance = false, mediaFlag = false))
        assertEquals(EmbedPolicy.Level.MEDIA, EmbedPolicy.levelFor("<div>x</div>", provenance = true, mediaFlag = true))
        assertEquals(EmbedPolicy.Level.TRUSTED, EmbedPolicy.levelFor("<div>x</div>", provenance = true, mediaFlag = false))
        // No provenance → never above UI even with flags (agent.md §11).
        assertEquals(EmbedPolicy.Level.UI, EmbedPolicy.levelFor("<div>x</div>", provenance = false, mediaFlag = true))
    }

    @Test
    fun sameOriginCompare() {
        assertTrue(EmbedPolicy.sameOrigin("https://ai.example.com/x", "https://ai.example.com/y"))
        assertFalse(EmbedPolicy.sameOrigin("https://ai.example.com", "https://ai.example.com.attacker.com"))
        assertFalse(EmbedPolicy.sameOrigin("https://ai.example.com:8443", "https://ai.example.com"))
    }
}