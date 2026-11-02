package dev.beacon.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table tests for the M1 segmenter (agent.md §16.3: rules + tests same commit). */
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