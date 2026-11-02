package dev.beacon.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Segmenter table tests (agent.md §16.3: every rule with its test, same commit). */
class SegmenterTest {
    @Test
    fun htmlFenceBecomesHtmlCard() {
        val parts = Segmenter.build(
            "before\n```html\n<!-- title: Weekly Plan -->\n<div>x</div>\n```\nafter",
            streaming = false,
        )
        assertEquals(3, parts.size) // before | card | after
        val card = parts[1] as ChatPart.HtmlCard
        assertEquals("Weekly Plan", card.title)
        assertTrue(card.html.orEmpty().contains("<div>x</div>"))
        assertTrue((parts[0] as ChatPart.Markdown).md == "before")
        assertTrue((parts[2] as ChatPart.Markdown).md == "after")
    }

    @Test
    fun mermaidAndChartFencesBecomeCards() {
        val parts = Segmenter.build(
            "```mermaid\ngraph TD; A-->B;\n```\n\n```chart\n{\"type\":\"bar\"}\n```",
            streaming = false,
        )
        assertEquals(2, parts.size) // mermaid card | chart card
        assertTrue((parts[0] as ChatPart.HtmlCard).html.orEmpty().contains("class=\"mermaid\""))
        assertTrue((parts[1] as ChatPart.HtmlCard).html.orEmpty().contains("new Chart"))
    }

    @Test
    fun openHtmlFencePreviewsAboveThreshold() {
        val payload = "<div>" + "x".repeat(220) + "</div>"
        val parts = Segmenter.build("```html\n" + payload, streaming = true)
        assertTrue(parts.last() is ChatPart.HtmlCard)
        // Below the threshold: not previewed yet.
        val small = Segmenter.build("```html\n<div>x</div>", streaming = true)
        assertTrue(small.filterIsInstance<ChatPart.HtmlCard>().isEmpty())
    }

    @Test
    fun mathWrappedIntoKatexSpans() {
        val parts = Segmenter.build("Euler: \$e^{i\\pi}+1=0\$", streaming = false)
        val md = parts.single() as ChatPart.Markdown
        assertTrue(md.md.contains("beacon-katex"))
        assertTrue(md.md.contains("data-block=\"inline\""))
        val display = (Segmenter.build("\$\$x^2\$\$", false).single() as ChatPart.Markdown).md
        assertTrue(display.contains("data-block=\"display\""))
    }

    @Test
    fun toolDetailsBlockBecomesToolChip() {
        val encoded = java.net.URLEncoder.encode("""["<div>ui!</div>"]""", "UTF-8")
        val md = """${'<'}details type="tool_calls" id="c1" title="make_chart(1)" embeds="$encoded"${'>'}""" +
            "\nargs here\n" + "</" + "details>"
        val parts = Segmenter.build(md, streaming = false)
        val call = parts.filterIsInstance<ChatPart.ToolCall>().single()
        assertEquals("make_chart", call.name)
        assertEquals("c1", call.callId)
        assertEquals(ToolStatus.Done, call.status)
        assertTrue(parts.filterIsInstance<ChatPart.HtmlCard>().single().html.orEmpty().contains("ui!"))
    }

    @Test
    fun thinkBlocksExtractReasoning() {
        val tagged = "atchet " + """${'<'}think${'>'}""" + "inner thoughts" + """${'<'}""" + "/think>"
        assertTrue(Segmenter.reasoningFromThinkBlocks(tagged).orEmpty().contains("inner thoughts"))
        assertTrue(Segmenter.reasoningFromThinkBlocks("no tags") == null)
    }
}