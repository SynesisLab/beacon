package dev.beacon.core.render

import kotlinx.serialization.json.JsonElement

/**
 * The render-part model — the contract between the chat engine and the UI
 * (agent.md §4: "everything else hangs off this"). The engine resolves the
 * active branch into a flat part list; every part renders as a composable and
 * persists as normalized JSON in Room (M2 adds the partsJson column).
 */
sealed interface ChatPart {
    data class Markdown(val md: String, val streaming: Boolean = false) : ChatPart

    /** Collapsible reasoning (server fold item or `<think>` blocks, §7.1). */
    data class Reasoning(
        val md: String,
        val collapsedByDefault: Boolean = true,
        val streaming: Boolean = false,
    ) : ChatPart

    /** Tool/function call; result may carry nested parts later (M2 full depth). */
    data class ToolCall(
        val callId: String? = null,
        val name: String,
        val args: String,
        val status: ToolStatus,
        val result: JsonElement? = null,
    ) : ChatPart

    /** Frontier-UI cards / artifacts / chart embeds (§7.2) — WebView pane. */
    data class HtmlCard(
        val id: String,
        val html: String? = null,
        val srcUrl: String? = null,
        val title: String? = null,
        val sandboxLevel: SandboxLevel = SandboxLevel.UI,
        val minHeightDp: Int = 120,
        val interactive: Boolean = true,
    ) : ChatPart

    /** display_file / uploads / generated images (§7.3). */
    data class File(
        val fileId: String,
        val name: String,
        val mime: String,
        val viewer: FileViewer,
    ) : ChatPart

    /** Full source list when known; count-only pill otherwise (M2-1 envelope shape). */
    data class Citations(val items: List<Source>, val reportedCount: Int? = null) : ChatPart
    data class Status(val text: String, val done: Boolean) : ChatPart
    data class GeneratedImage(val url: String, val prompt: String? = null) : ChatPart
    data class FollowUps(val items: List<String>) : ChatPart
}

enum class ToolStatus { Pending, Running, Done, Failed, Cancelled }

/** display_file routing (agent.md §7.3 — mime → viewer table). */
enum class FileViewer { Image, Pdf, Text, AudioVideo, Download }

/** Source payload for citations (source/citation envelopes + meta sources). */
data class Source(val title: String?, val url: String?)

enum class SandboxLevel { UI, MEDIA, TRUSTED }