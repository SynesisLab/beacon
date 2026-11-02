package dev.beacon.core.render

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table tests for the fold→part pipeline (agent.md §16.3, §6.2 precedence). */
class PartBuilderTest {
    private val json = Json

    private fun items(vararg jsons: String): List<kotlinx.serialization.json.JsonElement> =
        jsons.map { json.parseToJsonElement(it) }

    @Test
    fun legacyPath_reasoningThenMarkdown() {
        val parts = PartBuilder.build(
            FoldedMessage(contentMd = "Answer text", reasoningMd = "Why I think so", streaming = true),
        )
        assertEquals(listOf(ChatPart.Reasoning::class, ChatPart.Markdown::class), parts.map { it.javaClass.kotlin })
        assertTrue((parts[0] as ChatPart.Reasoning).md == "Why I think so")
        assertTrue((parts[1] as ChatPart.Markdown).streaming)
    }

    @Test
    fun outputPresent_evenEmpty_gatesRendering() {
        // §17: an emitted empty output[] is authoritative — no content render.
        val parts = PartBuilder.build(
            FoldedMessage(contentMd = "legacy accumulation", outputItems = emptyList(), outputSeen = true),
        )
        assertTrue(parts.filterIsInstance<ChatPart.Markdown>().isEmpty())
    }

    @Test
    fun outputs_messageAndReasoningRender() {
        val parts = PartBuilder.build(
            FoldedMessage(
                contentMd = "ignored legacy",
                outputSeen = true,
                outputItems = items(
                    """{"type":"reasoning","status":"completed","content":[{"type":"output_text","text":"think A"}]}""",
                    """{"type":"message","status":"in_progress","role":"assistant","content":[{"type":"output_text","text":"answer"}]}""",
                ),
                streaming = true,
            ),
        )
        assertEquals(listOf(ChatPart.Reasoning::class, ChatPart.Markdown::class), parts.map { it.javaClass.kotlin })
        val md = parts[1] as ChatPart.Markdown
        assertEquals("answer", md.md)
        assertTrue(md.streaming)
    }

    @Test
    fun toolCall_pairsWithItsOutput() {
        val parts = PartBuilder.build(
            FoldedMessage(
                outputSeen = true,
                outputItems = items(
                    """{"type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\"city\":\"SF\"}","status":"completed"}""",
                    """{"type":"function_call_output","call_id":"call_1","output":{"summary":"Sunny"}}""",
                ),
                streaming = false,
            ),
        )
        val call = parts.filterIsInstance<ChatPart.ToolCall>().single()
        assertEquals("get_weather", call.name)
        assertEquals(ToolStatus.Done, call.status)
        assertEquals("Sunny", (call.result as kotlinx.serialization.json.JsonObject)["summary"].toString().trim('"'))
    }

    @Test
    fun statusFirst_citationsAndFollowUpsLast() {
        val parts = PartBuilder.build(
            FoldedMessage(
                contentMd = "text",
                statusText = "Searching the web…",
                statusDone = false,
                sourceCount = 2,
                followUps = listOf("More?", "Details"),
            ),
        )
        assertTrue(parts.first() is ChatPart.Status)
        assertTrue(parts[parts.size - 2] is ChatPart.Citations)
        assertTrue(parts.last() is ChatPart.FollowUps)
    }

    @Test
    fun streamingBlankContentGetsCaretPart() {
        val parts = PartBuilder.build(FoldedMessage(contentMd = "", streaming = true))
        parts.filterIsInstance<ChatPart.Markdown>().single().let {
            assertTrue(it.md.isEmpty() && it.streaming)
        }
    }

    // display_file → ChatPart.File (verified: backend tools/builtin.py display_file)
    @Test
    fun displayFileOutputBecomesFilePart() {
        // Single-line: the tool result rides inside a JSON string (quotes escaped).
        val fileJson =
            """{"type":"file","source":"open_webui","exists":true,"displayed":true,""" +
                """"path":"/api/v1/files/abc-123/content","name":"report.pdf",""" +
                """"content_type":"application/pdf","mime_type":"application/pdf"}"""
        val embedded = fileJson.replace("\"", "\\\"")
        val parts = PartBuilder.build(
            FoldedMessage(
                outputSeen = true,
                outputItems = items(
                    """{"type":"function_call","call_id":"c1","name":"display_file","arguments":"{\"path\":\"abc-123\"}","status":"completed"}""",
                    """{"type":"function_call_output","call_id":"c1","output":"$embedded"}""",
                ),
            ),
        )
        val file = parts.filterIsInstance<ChatPart.File>().single()
        assertEquals("abc-123", file.fileId)
        assertEquals("report.pdf", file.name)
        assertEquals(FileViewer.Pdf, file.viewer)
        // The call itself still renders as a tool chip.
        assertEquals("display_file", parts.filterIsInstance<ChatPart.ToolCall>().single().name)
    }

    @Test
    fun fileViewerMimeMapping() {
        assertEquals(FileViewer.Image, DisplayFile.viewerForMime("image/png"))
        assertEquals(FileViewer.Pdf, DisplayFile.viewerForMime("application/pdf"))
        assertEquals(FileViewer.Text, DisplayFile.viewerForMime("text/plain"))
        assertEquals(FileViewer.Text, DisplayFile.viewerForMime("application/json"))
        assertEquals(FileViewer.AudioVideo, DisplayFile.viewerForMime("audio/mpeg"))
        assertEquals(FileViewer.Download, DisplayFile.viewerForMime("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
    }
}