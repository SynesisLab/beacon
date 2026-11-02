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
            val asString = (output as? JsonPrimitive)?.takeIf { it.isString }?.content
            val obj = when {
                asString != null -> json.parseToJsonElement(asString) as? JsonObject
                output is JsonObject -> output
                else -> null
            } ?: return null
            fromObject(obj)
        } catch (_: Exception) {
            null
        }
    }

    fun fromObject(obj: JsonObject): ChatPart.File? {
        if ((obj["type"] as? JsonPrimitive)?.content != "file") return null
        if ((obj["exists"] as? JsonPrimitive)?.content == "false") return null
        val path = (obj["path"] as? JsonPrimitive)?.content ?: return null
        val fileId = Regex("/api/v1/files/([^/]+)/content").find(path)?.groupValues?.get(1) ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.content ?: fileId
        val mime = (obj["mime_type"] as? JsonPrimitive)?.content
            ?: (obj["content_type"] as? JsonPrimitive)?.content
            ?: "application/octet-stream"
        return ChatPart.File(
            fileId = fileId,
            name = name,
            mime = mime,
            viewer = viewerForMime(mime),
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