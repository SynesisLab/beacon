package dev.beacon.core.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visualization built-in tool (0.3): a completed `render_visualization`
 * call is a full-bleed card, never a JSON tool row; a pending / partial-args
 * call stands in as the plain tool row; the accepted output never leaks a
 * junk row. Wire proof: backend/open_webui/tools/visualizations.py
 * (render_visualization — title/html/libraries, `{'status':'accepted'}`) and
 * src/lib/utils/visualizations/{dispatch,frame,arguments}.ts.
 */
class PartBuilderVisualizationTest {

    private fun fco(callId: String, status: String = "completed") = buildJsonObject {
        put("type", JsonPrimitive("function_call_output"))
        put("call_id", JsonPrimitive(callId))
        put("status", JsonPrimitive(status))
        put("output", JsonPrimitive("""{"status":"accepted"}"""))
    }

    private fun directCall(
        callId: String,
        argsJson: String,
        status: String = "completed",
    ) = buildJsonObject {
        put("type", JsonPrimitive("function_call"))
        put("call_id", JsonPrimitive(callId))
        put("name", JsonPrimitive("render_visualization"))
        put("arguments", JsonPrimitive(argsJson))
        put("status", JsonPrimitive(status))
    }

    private fun dispatcherCall(
        callId: String,
        argsJson: String,
        status: String = "completed",
    ) = buildJsonObject {
        put("type", JsonPrimitive("function_call"))
        put("call_id", JsonPrimitive(callId))
        put("tool_type", JsonPrimitive("builtin"))
        put("tool_id", JsonPrimitive("builtin:call_tool"))
        put(
            "tool_targets",
            buildJsonObject {
                put(
                    "render_visualization",
                    buildJsonObject {
                        put("tool_type", JsonPrimitive("builtin"))
                        put("tool_id", JsonPrimitive("builtin:render_visualization"))
                    },
                )
            },
        )
        put("arguments", JsonPrimitive(argsJson))
        put("status", JsonPrimitive(status))
    }

    private val vizArgs =
        """{"title":"Fee calculator","html":"<div>hi<script>1+1</script></div>","libraries":["chartjs"]}"""

    private fun items(vararg elements: kotlinx.serialization.json.JsonElement) =
        FoldedMessage(outputSeen = true, outputItems = elements.toList())

    @Test
    fun `accepted direct call becomes a visualization card with libraries`() {
        val parts = PartBuilder.build(items(directCall("c1", vizArgs), fco("c1")))
        assertFalse("no tool row", parts.any { it is ChatPart.ToolCall && it.callId == "c1" })
        val card = parts.filterIsInstance<ChatPart.HtmlCard>().first()
        assertEquals(CardKind.Visualization, card.kind)
        assertEquals("Fee calculator", card.title)
        assertEquals(listOf("chartjs"), card.libraries)
        assertTrue(card.html!!.contains("1+1"))
        assertEquals("viz-c1", card.id)
    }

    @Test
    fun `pending call stays a tool row until args are complete`() {
        val parts = PartBuilder.build(items(directCall("c2", vizArgs, status = "in_progress")))
        val row = parts.filterIsInstance<ChatPart.ToolCall>().first()
        assertEquals("c2", row.callId)
        assertEquals(ToolStatus.Running, row.status)
        assertTrue(parts.filterIsInstance<ChatPart.HtmlCard>().isEmpty())
    }

    @Test
    fun `pending call completes into the card when its output arrives`() {
        // Order Web sees mid-stream: (in_progress call) → … [later fold with
        // the completed pair]. This fold carries completed+output.
        val parts = PartBuilder.build(items(directCall("c3", vizArgs), fco("c3")))
        assertEquals(1, parts.filterIsInstance<ChatPart.HtmlCard>().size)
    }

    @Test
    fun `malformed args keep the tool row`() {
        val parts = PartBuilder.build(
            items(directCall("c4", "{\"title\":\"x\",\"html\":\"\"}"), fco("c4")),
        )
        assertTrue(parts.any { it is ChatPart.ToolCall && it.callId == "c4" })
        assertTrue(parts.filterIsInstance<ChatPart.HtmlCard>().isEmpty())
    }

    @Test
    fun `batched builtin dispatcher form renders the card`() {
        val wrapped = """{"calls":[{"tool_name":"render_visualization","params":$vizArgs}]}"""
        val parts = PartBuilder.build(
            items(dispatcherCall("c5", wrapped), fco("c5")),
        )
        val card = parts.filterIsInstance<ChatPart.HtmlCard>().first()
        assertEquals(CardKind.Visualization, card.kind)
        assertEquals("Fee calculator", card.title)
    }

    @Test
    fun `orphan output (call in an earlier fold) keeps web pairing behavior`() {
        // PartBuilder folds a WHOLE output snapshot each pass; a call deleted
        // server-side mid-stream leaves an orphan output — the pre-existing
        // pairing fallback names it "tool" and carries the result.
        val parts = PartBuilder.build(items(fco("c6")))
        val row = parts.filterIsInstance<ChatPart.ToolCall>().first()
        assertEquals("c6", row.callId)
        assertEquals("tool", row.name)
        assertTrue(row.result.toString().contains("accepted"))
    }
}
