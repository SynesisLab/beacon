package dev.beacon.core.render

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-verified display_file payload (2026-11-06, chat f18050dd, Room DB):
 * a TERMINAL file display — function_call_output wraps the tool's JSON in the
 * OpenAI content-parts array; instance files instead come back as
 * `type:'file'` output items (middleware ToolOutputFile).
 */
class DisplayFileFoldTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val terminalPayload = """
        [
          {"type": "function_call", "call_id": "call_040y1c46", "name": "display_file",
           "arguments": "{\"path\":\"/home/user/beacon-ui-design.md\"}", "status": "completed"},
          {"type": "function_call_output", "call_id": "call_040y1c46",
           "output": [{"type": "input_text", "text":
             "{\"path\": \"/home/user/beacon-ui-design.md\", \"exists\": true, \"type\": \"file\", \"source\": \"open_terminal\", \"displayed\": false, \"terminal_selector\": \"b8e5d3a4-71de-498b-b5ab-3fbc866aedbe\", \"terminal_id\": \"b8e5d3a4-71de-498b-b5ab-3fbc866aedbe\", \"session_id\": \"f18050dd\", \"full_path\": \"/home/user/beacon-ui-design.md\", \"name\": \"beacon-ui-design.md\", \"mime_type\": \"text/markdown\", \"content_type\": \"text/markdown\"}"
           }]},
          {"type": "message", "status": "completed", "content": [{"type": "output_text", "text": "Here is the design doc."}]}
        ]
    """.trimIndent()

    private val instancePayload = """
        [{"type": "file", "exists": true, "displayed": true,
          "path": "/api/v1/files/8d747b83-dd07-4fde-a414-e14cc73a26be/content",
          "name": "User Profile.md", "source": "open_webui",
          "content_type": "text/markdown", "mime_type": "text/markdown"}]
    """.trimIndent()

    @Test
    fun `terminal display_file output folds to a File part`() {
        val parts = PartBuilder.build(
            FoldedMessage(outputSeen = true, outputItems = json.parseToJsonElement(terminalPayload) as kotlinx.serialization.json.JsonArray),
            json,
        )
        val file = parts.filterIsInstance<ChatPart.File>().singleOrNull()
        assertTrue("expected a File part, got $parts", file != null)
        assertTrue(file!!.name == "beacon-ui-design.md")
        assertTrue(file.mime == "text/markdown")
        assertTrue(file.terminalServerId == "b8e5d3a4-71de-498b-b5ab-3fbc866aedbe")
        assertTrue(file.terminalPath == "/home/user/beacon-ui-design.md")
        assertTrue("display_file must render a File part, never JSON text (agent.md 7.1)", parts.none { it is ChatPart.Markdown && it.md.contains("\"terminal_selector\"") })
    }

    @Test
    fun `instance file output item folds to a File part`() {
        val parts = PartBuilder.build(
            FoldedMessage(outputSeen = true, outputItems = json.parseToJsonElement(instancePayload) as kotlinx.serialization.json.JsonArray),
            json,
        )
        val file = parts.filterIsInstance<ChatPart.File>().singleOrNull()
        assertTrue("expected a File part, got $parts", file != null)
        assertTrue(file!!.fileId == "8d747b83-dd07-4fde-a414-e14cc73a26be")
    }

    @Test
    fun `context compaction output item folds to a Compaction part`() {
        // middleware.py:6046 shape: {type:'open_webui:context_compaction', id, call_id, summary}
        val payload = """
            [{"type":"open_webui:context_compaction","id":"cc_1","call_id":"call_9","summary":"Earlier the user asked about the UI audit."},
             {"type":"message","status":"completed","role":"assistant","content":[{"type":"output_text","text":"Answer"}]}]
        """.trimIndent()
        val parts = PartBuilder.build(
            FoldedMessage(outputSeen = true, outputItems = json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonArray),
            json,
        )
        val compaction = parts.filterIsInstance<ChatPart.Compaction>().singleOrNull()
        assertTrue("expected a Compaction part, got $parts", compaction != null)
        assertTrue(compaction!!.summary == "Earlier the user asked about the UI audit.")
    }
}
