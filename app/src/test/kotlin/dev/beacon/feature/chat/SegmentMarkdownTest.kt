package dev.beacon.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table tests for the M1 segmenter . */
class SegmentMarkdownTest {
    @Test
    fun plainParagraphs() {
        val blocks = segmentMarkdown("hello\nworld\n\nsecond")
        assertEquals(
            listOf(
                TextBlock(BlockKind.Paragraph, "hello\nworld"),
                TextBlock(BlockKind.Paragraph, "second"),
            ),
            blocks,
        )
    }

    @Test
    fun fencedCodeWithLanguage() {
        val blocks = segmentMarkdown("before\n```kotlin\nval a = 1\nval b = 2\n```\nafter")
        assertEquals(3, blocks.size)
        assertEquals(BlockKind.Code, blocks[1].kind)
        assertEquals("kotlin", blocks[1].language)
        assertEquals("val a = 1\nval b = 2", blocks[1].text)
        assertTrue(blocks[0].text == "before" && blocks[2].text == "after")
    }

    @Test
    fun openFenceStillStreamingsAsPreview() {
        val blocks = segmentMarkdown("```py\ndef f():\n    pass\n")
        assertEquals(1, blocks.size)
        assertEquals(BlockKind.Code, blocks[0].kind)
        assertEquals("py", blocks[0].language)
        assertEquals("def f():\n    pass", blocks[0].text)
    }

    @Test
    fun tildesAlsoFence() {
        val blocks = segmentMarkdown("~~~\ncode\n~~~")
        assertEquals(1, blocks.size)
        assertEquals("code", blocks[0].text)
    }

    @Test
    fun blankMarkdownYieldsNothing() {
        assertTrue(segmentMarkdown("").isEmpty())
        assertTrue(segmentMarkdown("   \n  \n").isEmpty())
    }
}
/** GFM coverage (2026-11-06): tables, quotes, task/nested lists, HR, setext. */
class SegmentMarkdownGfmTest {
    @Test
    fun pipeTableParsesHeaderAlignsAndRows() {
        val blocks = segmentMarkdown(
            "| Name | Age | City |\n" +
                "|:-----|:---:|-----:|\n" +
                "| Ada  | 36  | NYC  |\n" +
                "| Bob  | 41  | SF   |\n" +
                "after",
        )
        assertEquals(2, blocks.size)
        val t = blocks[0]
        assertEquals(BlockKind.Table, t.kind)
        assertEquals(listOf("Name", "Age", "City"), t.header)
        assertEquals(listOf(0, 1, 2), t.aligns) // :--- left, :---: center, ---: right
        assertEquals(listOf(listOf("Ada", "36", "NYC"), listOf("Bob", "41", "SF")), t.rows)
        assertEquals(TextBlock(BlockKind.Paragraph, "after"), blocks[1])
    }

    @Test
    fun blockquoteAccumulatesAndStripsMarker() {
        val blocks = segmentMarkdown("> line one\n> line two\n\nplain")
        assertEquals(2, blocks.size)
        assertEquals(BlockKind.Quote, blocks[0].kind)
        assertEquals("line one\nline two", blocks[0].text)
        assertEquals(BlockKind.Paragraph, blocks[1].kind)
    }

    @Test
    fun taskListAndNestedLevels() {
        val blocks = segmentMarkdown("- [x] done thing\n- [ ] todo thing\n  - nested item\n- plain")
        assertEquals(1, blocks.size)
        val items = blocks[0].items
        assertEquals(4, items.size)
        assertEquals(1, items[0].checked)
        assertEquals(0, items[1].checked)
        assertEquals(1, items[2].level)
        assertEquals(0, items[3].level)
        assertEquals(-1, items[3].checked)
    }

    @Test
    fun horizontalRuleAndSetextHeadings() {
        val blocks = segmentMarkdown("Title\n=====\n\nSub\n---\n\npara\n\n***\n")
        assertEquals(4, blocks.size)
        assertEquals(BlockKind.Heading, blocks[0].kind)
        assertEquals(1, blocks[0].headingLevel)
        assertEquals(BlockKind.Heading, blocks[1].kind)
        assertEquals(2, blocks[1].headingLevel)
        assertEquals(BlockKind.Paragraph, blocks[2].kind)
        assertEquals(BlockKind.Rule, blocks[3].kind)
    }

    @Test
    fun inlineMarks_strikeBoldUnderscoreItalicAutolinkHtmlStripped() {
        fun plain(s: String) = inlineText(s).text
        assertEquals("struck", plain("~~struck~~"))
        assertEquals("bold", plain("__bold__"))
        assertEquals("it", plain("_it_"))
        assertEquals("snake_case_var stays", plain("snake_case_var stays"))
        assertEquals("https://x.test/a", plain("<https://x.test/a>"))
        assertEquals("a \n b", plain("a <br> b")) // <br> becomes a line break
        assertEquals("a  b", plain("a <div> b")) // other tags stripped
        assertEquals("**x**", plain("`**x**`")) // code span wins: content kept verbatim, NOT bolded
    }

    @Test
    fun imageTokenDoesNotBecomeLink() {
        val s = inlineText("see ![pic](https://img.test/a.png) now")
        assertTrue(s.text.contains("![pic](https://img.test/a.png)"))
        assertEquals(0, s.getLinkAnnotations(0, s.length).size)
    }
}
