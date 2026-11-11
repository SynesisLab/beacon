package dev.beacon.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InstanceUrlNormalizationTest {
    @Test
    fun addsHttpsWhenNoScheme() {
        assertEquals("https://ai.example.com", normalizeInstanceUrl("ai.example.com"))
        assertEquals("https://192.168.1.10:8080", normalizeInstanceUrl("192.168.1.10:8080"))
    }

    @Test
    fun keepsExplicitScheme() {
        assertEquals("http://192.168.1.10:8080", normalizeInstanceUrl("http://192.168.1.10:8080"))
        assertEquals("https://ai.example.com", normalizeInstanceUrl("https://ai.example.com"))
    }

    @Test
    fun keepsExplicitSchemeCaseInsensitive() {
        // Keyboards auto-capitalize the first letter: "Http://…" must keep its
        // scheme, not fall through to "https://Http://…".
        assertEquals("http://192.168.1.10:8080", normalizeInstanceUrl("Http://192.168.1.10:8080"))
        assertEquals("http://192.168.1.10:8080", normalizeInstanceUrl("HTTP://192.168.1.10:8080"))
        assertEquals("https://ai.example.com", normalizeInstanceUrl("HTTPS://ai.example.com"))
        // Scheme lowercased, rest of the URL left exactly as typed (paths are case-sensitive).
        assertEquals("https://my.host/Path/Q?=1", normalizeInstanceUrl("HTTPS://my.host/Path/Q?=1"))
    }

    @Test
    fun trimsWhitespaceAndTrailingSlash() {
        assertEquals("https://ai.example.com", normalizeInstanceUrl("  https://ai.example.com/  "))
        assertEquals("https://ai.example.com", normalizeInstanceUrl("https://ai.example.com////"))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", normalizeInstanceUrl("   "))
        assertEquals("", normalizeInstanceUrl(""))
    }
}