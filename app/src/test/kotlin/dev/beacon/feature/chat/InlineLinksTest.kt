package dev.beacon.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link parsing in the transcript renderer (user report 2026-11-03). */
class InlineLinksTest {
    @Test
    fun markdownLink_becomesLinkSpan() {
        val s = inlineText("See [the docs](https://example.com/docs) now")
        val links = s.getLinkAnnotations(0, s.length)
        assertTrue(links.isNotEmpty())
        // The label replaces the bracket syntax; the URL is inside the link range.
        assertEquals("See the docs now", s.text)
        val urlAnnotation = links.first().item as androidx.compose.ui.text.LinkAnnotation.Url
        assertEquals("https://example.com/docs", urlAnnotation.url)
    }

    @Test
    fun bareUrl_becomesLinkSpan() {
        val s = inlineText("open https://example.com/x?y=1 here")
        assertTrue(s.getLinkAnnotations(0, s.length).isNotEmpty())
        assertTrue(s.text.startsWith("open https://example.com"))
    }

    @Test
    fun urlInsideInlineCode_notLinked() {
        val s = inlineText("use `https://example.com/a` verbatim")
        assertTrue(s.getLinkAnnotations(0, s.length).isEmpty())
    }

    @Test
    fun boldAndLinks_coexist() {
        val s = inlineText("**bold** and [b2](https://e.com) end")
        assertTrue(s.text.contains("bold"))
        assertTrue(s.getLinkAnnotations(0, s.length).size == 1)
    }
}