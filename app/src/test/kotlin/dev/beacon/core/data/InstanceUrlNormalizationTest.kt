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