package dev.beacon.core.render

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-frame channel (function_call_output.embeds / message.embeds): the
 * Frontier UI / HTMLResponse tool path, verified against today's on-device
 * chat DB (2026-11-04) — middleware.py:745-752 → :5844-5850.
 */
class PartBuilderEmbedsTest {

    private fun fcoWithEmbeds(callId: String, embeds: List<String>, rawOutput: String?) =
        kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("function_call_output"))
            put("call_id", kotlinx.serialization.json.JsonPrimitive(callId))
            put("status", kotlinx.serialization.json.JsonPrimitive("completed"))
            put(
                "output",
                if (rawOutput == null) {
                    kotlinx.serialization.json.JsonNull
                } else {
                    Json.parseToJsonElement(rawOutput)
                },
            )
            if (embeds.isNotEmpty()) {
                put(
                    "embeds",
                    kotlinx.serialization.json.JsonArray(
                        embeds.map { kotlinx.serialization.json.JsonPrimitive(it) },
                    ),
                )
            }
        }

    private fun call(callId: String) = kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("function_call"))
        put("call_id", kotlinx.serialization.json.JsonPrimitive(callId))
        put("name", kotlinx.serialization.json.JsonPrimitive("render_frontier_ui"))
        put("arguments", kotlinx.serialization.json.JsonPrimitive("{\"spec\":{\"title\":\"x\"}}"))
        put("status", kotlinx.serialization.json.JsonPrimitive("completed"))
    }

    @Test
    fun frontierUiCardEmbedsBecomeMediaCards() {
        val cardHtml = """<!DOCTYPE html><html><head><title>card</title></head><body>GLM vs Framework</body></html>"""
        val folded = FoldedMessage(
            outputSeen = true,
            outputItems = listOf(
                call("call_t1p1btmo"),
                fcoWithEmbeds(
                    "call_t1p1btmo",
                    listOf(cardHtml),
                    """[{"type":"input_text","text":"Frontier UI card is rendered (card id: c2212fe0)."}]""",
                ),
            ),
        )
        val parts = PartBuilder.build(folded)
        val embeds = parts.filterIsInstance<ChatPart.HtmlCard>()
        assertEquals(1, embeds.size)
        val embed = embeds.single()
        assertEquals(CardKind.Embed, embed.kind)
        assertEquals(SandboxLevel.MEDIA, embed.sandboxLevel)
        assertTrue(embed.html.orEmpty().contains("GLM vs Framework"))
        // The model-facing wrapper text unwraps to prose for the chip.
        val chip = parts.filterIsInstance<ChatPart.ToolCall>().single()
        assertEquals(
            "Frontier UI card is rendered (card id: c2212fe0).",
            (chip.result as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }

    @Test
    fun multipleEmbedsRenderInOrder() {
        val folded = FoldedMessage(
            outputSeen = true,
            outputItems = listOf(
                call("c1"),
                fcoWithEmbeds("c1", listOf("<div>one</div>", "<div>two</div>"), null),
            ),
        )
        val embeds = PartBuilder.build(folded).filterIsInstance<ChatPart.HtmlCard>()
        assertEquals(2, embeds.size)
        assertTrue(embeds[0].html.orEmpty().contains("one"))
        assertTrue(embeds[1].html.orEmpty().contains("two"))
    }

    @Test
    fun messageItemEmbedsAlsoBecomeCards() {
        val message = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("message"))
            put(
                "content",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.buildJsonObject {
                            put("type", kotlinx.serialization.json.JsonPrimitive("output_text"))
                            put("text", kotlinx.serialization.json.JsonPrimitive("hi"))
                        },
                    ),
                ),
            )
            put(
                "embeds",
                kotlinx.serialization.json.JsonArray(
                    listOf(kotlinx.serialization.json.JsonPrimitive("<div>ui</div>")),
                ),
            )
        }
        val folded = FoldedMessage(outputSeen = true, outputItems = listOf(message))
        val embeds = PartBuilder.build(folded).filterIsInstance<ChatPart.HtmlCard>()
        assertEquals(1, embeds.size)
        assertEquals(CardKind.Embed, embeds.single().kind)
    }

    @Test
    fun nonInputTextPartArraysStayUntouched() {
        val wrapper = Json.parseToJsonElement("""[{"type":"image_url","image_url":{"url":"https://x"}}]""")
        assertEquals(wrapper, unwrapInputTextParts(wrapper))
        val single = kotlinx.serialization.json.JsonPrimitive("plain")
        assertEquals(single, unwrapInputTextParts(single))
    }
}
