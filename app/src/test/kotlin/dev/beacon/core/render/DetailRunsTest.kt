package dev.beacon.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** detailRuns: consecutive thinking/tool tokens group like the web (ConsecutiveDetailsGroup). */
class DetailRunsTest {

    private val reasoning = ChatPart.Reasoning("thoughts")
    private fun call(name: String) =
        ChatPart.ToolCall(name = name, args = "{}", status = ToolStatus.Done)

    @Test
    fun consecutiveDetailsCollapse_intoRuns() {
        val chunks = detailRuns(
            listOf(reasoning, call("render_frontier_ui"), ChatPart.Reasoning("more")),
        )
        assertEquals(1, chunks.size)
        val run = chunks.single() as DetailRun
        assertEquals(3, run.parts.size)
    }

    @Test
    fun nonDetailPartsFlushTheRun() {
        val chunks = detailRuns(
            listOf(
                reasoning, call("a"),
                ChatPart.Markdown("answer"),
                call("b"),
            ),
        )
        assertEquals(3, chunks.size)
        val first = chunks[0] as DetailRun
        assertTrue(first.parts[0] is ChatPart.Reasoning)
        assertEquals("a", (first.parts[1] as ChatPart.ToolCall).name)
        assertEquals("answer", (chunks[1] as ChatPart.Markdown).md)
        assertEquals("b", ((chunks[2] as DetailRun).parts.single() as ChatPart.ToolCall).name)
    }

    @Test
    fun reasoningOnlyRunsStayAsPlainParts() {
        // A solo thinking card keeps the old look; tools required for the group row.
        val chunks = detailRuns(listOf(reasoning, ChatPart.Reasoning("second")))
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it is ChatPart.Reasoning })
    }

    private fun assertTrue(all: Boolean) = org.junit.Assert.assertTrue(all)
}
