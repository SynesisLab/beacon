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
        val mermaid = parts[0] as ChatPart.HtmlCard
        val chart = parts[1] as ChatPart.HtmlCard
        assertTrue(mermaid.html.orEmpty().contains("class=\"mermaid\""))
        assertEquals(CardKind.Mermaid, mermaid.kind)
        // Chart config rides an application/json script tag (bootstrap renders
        // it) — `</script` in a config string can never close the wrapper.
        assertTrue(chart.html.orEmpty().contains("beacon-chart-config"))
        assertEquals(CardKind.Chart, chart.kind)
    }

    @Test
    fun mermaidCodeIsTextEscaped() {
        val parts = Segmenter.build(
            "```mermaid\nflowchart A-->B[\"x<y & z\"]\n```",
            streaming = false,
        )
        val card = parts.single() as ChatPart.HtmlCard
        assertTrue(card.html.orEmpty().contains("x&lt;y &amp; z"))
    }

    @Test
    fun chartConfigSurvivesScriptCloseInjection() {
        val parts = Segmenter.build("```chart\n{\"labels\":[\"</script>\"]}\n```", streaming = false)
        val html = (parts.single() as ChatPart.HtmlCard).html.orEmpty()
        // The config's `</script>` escapes as `<\/script>` (valid JSON), so the
        // wrapper stays closed exactly once.
        assertTrue(html.contains("<\\/script>"))
        assertTrue(html.contains("beacon-chart-config"))
        // The wrapper's own terminator is the LAST one.
        assertEquals(1, Regex("<\\/script>").findAll(html).count())
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
    fun mathStaysRawInMarkdownParts() {
        // The old beacon-katex span wrapping leaked literal HTML into bubbles
        // (MiniMarkdown renders parts, not the card shell) — removed; raw math
        // text waits on a compose renderer (M5).
        val parts = Segmenter.build("Euler: \$e^{i\\pi}+1=0\$", streaming = false)
        val md = parts.single() as ChatPart.Markdown
        assertTrue(md.md.contains("\$e^{i\\pi}+1=0\$"))
        assertTrue(!md.md.contains("beacon-katex"))
        val display = Segmenter.build("\$\$x^2\$\$", false).single() as ChatPart.Markdown
        assertTrue(display.md.contains("\$\$x^2\$\$"))
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
        val embed = parts.filterIsInstance<ChatPart.HtmlCard>().single()
        assertEquals(CardKind.Embed, embed.kind)
        assertTrue(embed.html.orEmpty().contains("ui!"))
    }

    @Test
    fun thinkBlocksExtractReasoning() {
        val tagged = "atchet " + """${'<'}think${'>'}""" + "inner thoughts" + """${'<'}""" + "/think>"
        assertTrue(Segmenter.reasoningFromThinkBlocks(tagged).orEmpty().contains("inner thoughts"))
        assertTrue(Segmenter.reasoningFromThinkBlocks("no tags") == null)
    }

    @Test
    fun textModeReasoningDetailsBecomeThinking() {
        // Web parity (ContentRenderer.svelte:24-68): text-mode details lift OUT
        // of the plain content; type="reasoning" = the collapsible Thinking.
        val md = "${'<'}" + """details type="reasoning">secret thoughts""" + "${'<'}/details>\nAnswer here"
        val parts = Segmenter.build(md, streaming = false)
        val reasoning = parts.filterIsInstance<ChatPart.Reasoning>().single()
        assertTrue(reasoning.md.contains("secret thoughts"))
        val answer = parts.filterIsInstance<ChatPart.Markdown>().single()
        assertTrue(answer.md.contains("Answer here"))
        assertTrue(!answer.md.contains("details"))
    }

    @Test
    fun codeInterpreterDetailsStripped() {
        val md = "${'<'}" + """details type="code_interpreter">code here""" + "${'<'}/details>\nFinal"
        val parts = Segmenter.build(md, streaming = false)
        assertTrue(parts.filterIsInstance<ChatPart.Markdown>().single().md.contains("Final"))
        assertTrue(parts.none { it is ChatPart.HtmlCard })
        assertTrue(parts.none { it is ChatPart.ToolCall })
    }
}