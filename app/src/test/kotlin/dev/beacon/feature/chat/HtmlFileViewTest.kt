package dev.beacon.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlFileViewTest {

    @Test
    fun fullDocInjectsThemeSeedRightAfterHead() {
        val doc = themeDocument("<html><head><title>x</title></head><body>hi</body></html>", dark = true)
        val headEnd = doc.indexOf("</head>")
        val seed = doc.indexOf("classList.toggle('dark',true)")
        assertTrue("seed must land inside <head>, before </head>", seed in 0 until headEnd)
        assertTrue(doc.contains("<meta name=\"color-scheme\" content=\"dark\">"))
        assertTrue("original head content preserved", doc.contains("<title>x</title>"))
    }

    @Test
    fun lightSchemeSeedsLight() {
        val doc = themeDocument("<html><head></head><body>x</body></html>", dark = false)
        assertTrue(doc.contains("localStorage.setItem('theme','light')"))
        assertTrue(doc.contains("classList.toggle('dark',false)"))
        assertTrue(doc.contains("<meta name=\"color-scheme\" content=\"light\">"))
    }

    @Test
    fun fragmentGetsTransparentBackgroundAndViewport() {
        val doc = themeDocument("<div>no head here</div>", dark = false)
        assertTrue("fragment must let the app theme show through", doc.contains("background: transparent"))
        assertTrue(doc.contains("name=\"viewport\""))
        assertFalse("fragment stays a fragment", doc.contains("<head>"))
        assertTrue("payload prepends when there is no <head>", doc.startsWith("<script>"))
    }

    @Test
    fun fullDocWithoutViewportMetaGetsOne() {
        val doc = themeDocument("<html><head><meta charset=\"utf-8\"></head><body>x</body></html>", dark = true)
        assertTrue(doc.contains("name=\"viewport\""))
    }
}
