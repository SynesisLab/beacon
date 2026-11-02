package dev.beacon.core.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * display_file → [ChatPart.File] (agent.md §7.1: "never dump it as JSON text").
 * Result shape verified in backend/open_webui/tools/builtin.py `display_file`:
 * `{type:'file', exists, displayed, path:'/api/v1/files/<id>/content',
 *   name, content_type/mime_type, [page]}` — returned as a JSON *string* in the
 * function_call_output payload.
 */
object DisplayFile {
    fun parse(output: kotlinx.serialization.json.JsonElement?, json: Json): ChatPart.File? {
        if (output == null) return null
        return try {
            // Shapes seen in the wild (docs/protocol-notes.md):
            // 1. the display_file object itself (server-converted `type:'file'` output items);
            // 2. a JSON string of it (raw tool result);
            // 3. an ARRAY of content parts [{type:'input_text', text:"<json string>"}]
            //    — the OpenAI content-parts wrapper (verified on-device 2026-11-02).
            val extracted: String? = when {
                output is kotlinx.serialization.json.JsonArray -> output.toList()
                    .joinToString("") { part ->
                        ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
                    }
                (output as? JsonPrimitive)?.isString == true -> output.content
                output is JsonObject -> return fromObject(output)
                else -> null
            }
            val obj = json.parseToJsonElement(extracted ?: return null) as? JsonObject ?: return null
            fromObject(obj)
        } catch (_: Exception) {
            null
        }
    }

    fun fromObject(obj: JsonObject): ChatPart.File? {
        if ((obj["type"] as? JsonPrimitive)?.content != "file") return null
        if ((obj["exists"] as? JsonPrimitive)?.content == "false") return null
        val path = (obj["path"] as? JsonPrimitive)?.content ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.content ?: path.substringAfterLast('/')
        val mime = (obj["mime_type"] as? JsonPrimitive)?.content
            ?: (obj["content_type"] as? JsonPrimitive)?.content
            ?: "application/octet-stream"

        // Instance files: full native viewer. Terminal files: read via the OWUI
        // terminal proxy (routers/terminals.py; inline viewing promoted into M2).
        val fileId = Regex("/api/v1/files/([^/]+)/content").find(path)?.groupValues?.get(1)
        val terminalId = (obj["terminal_id"] as? JsonPrimitive)?.content
            ?: (obj["terminal_selector"] as? JsonPrimitive)?.content
        return ChatPart.File(
            fileId = fileId ?: "",
            name = name,
            mime = mime,
            viewer = viewerForMime(mime),
            terminalServerId = terminalId?.takeIf { fileId == null },
            terminalPath = path.takeIf { fileId == null },
        )
    }

    /** agent.md §7.3 mime → viewer table (office family falls through to Download). */
    fun viewerForMime(mime: String): FileViewer = when {
        mime.startsWith("image/") -> FileViewer.Image
        mime == "application/pdf" -> FileViewer.Pdf
        mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime.contains("javascript") ||
            mime.contains("yaml") ||
            mime.endsWith("+xml") -> FileViewer.Text
        mime.startsWith("audio/") || mime.startsWith("video/") -> FileViewer.AudioVideo
        else -> FileViewer.Download
    }
}