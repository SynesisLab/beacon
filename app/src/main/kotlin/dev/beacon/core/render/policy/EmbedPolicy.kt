package dev.beacon.core.render.policy

/**
 * The embed policy engine (agent.md §7.2) — pure rules verified against the web
 * client (FullHeightIframe.svelte: URL-vs-HTML auto-detect; scripts on;
 * allow-same-origin FALSE unless trusted; CSP injection) and agent.md's
 * mobile-safe overlay: popups/links never navigate the frame — they go external
 * unless the target is the instance origin (§11: same-origin credentials only).
 */
/** In-pane navigation verdicts. */
enum class NavigationDecision { LoadInFrame, OpenExternal, Block }

sealed interface EmbedPolicy {

    /**
     * The web auto-detects URL vs raw HTML (FullHeightIframe).
     * `http(s)` and app-served paths load by URL; anything else is raw HTML.
     */
    sealed interface Source {
        data class Url(val url: String) : Source
        data class Html(val html: String) : Source
    }

    companion object {
        private val URL_PREFIXES = listOf("http://", "https://", "//", "/")
        private val BLOCKED_SCHEMES = listOf(
            "file://", "content://", "intent://", "javascript:", "data:",
        )

        fun source(raw: String): Source {
            val trimmed = raw.trim()
            val isUrl = URL_PREFIXES.any { trimmed.startsWith(it) } &&
                !BLOCKED_SCHEMES.any { trimmed.startsWith(it) }
            return if (isUrl) Source.Url(normalize(trimmed)) else Source.Html(trimmed)
        }

        private fun normalize(url: String): String = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> url // instance-relative — resolved by the host layer
            else -> url
        }

        fun blocked(url: String): Boolean = BLOCKED_SCHEMES.any { url.lowercase().trim().startsWith(it) }

        /**
         * Navigation decision for the pane (agent.md §7.2): instance-origin
         * content stays in-frame; automatic non-user navigations load in-frame;
         * user-activated links/popups to non-instance targets open outside
         * (Custom Tabs); blocked schemes never load.
         */
        fun navigationDecision(
            url: String,
            isUserGesture: Boolean,
            isPopup: Boolean,
            instanceUrl: String?,
        ): NavigationDecision {
            if (blocked(url)) return NavigationDecision.Block
            if (instanceUrl != null && sameOrigin(url, instanceUrl)) return NavigationDecision.LoadInFrame
            if (!isUserGesture && !isPopup) return NavigationDecision.LoadInFrame
            return NavigationDecision.OpenExternal
        }

        /** java.net-based same-origin compare (pure JVM — this file stays testable). */
        fun sameOrigin(a: String, b: String): Boolean = try {
            val ua = java.net.URI(if (a.startsWith("//")) "https:$a" else a)
            val ub = java.net.URI(b)
            ua.scheme.equals(ub.scheme, true) &&
                ua.host.equals(ub.host, true) &&
                (ua.port.takeIf { it != -1 } ?: 0) == (ub.port.takeIf { it != -1 } ?: 0)
        } catch (_: Exception) {
            false
        }

        // (moved to top-level NavigationDecision)

        /**
         * agent.md §11: elevation requires server-side provenance. The web's
         * default is allowSameOrigin=false — Beacon's levels mirror that:
         * UI always; MEDIA/TRUSTED only when the server stamped the embed.
         */
        fun levelFor(html: String, provenance: Boolean, mediaFlag: Boolean): Level = when {
            provenance && mediaFlag -> Level.MEDIA
            provenance -> Level.TRUSTED
            else -> Level.UI
        }
    }

    enum class Level { UI, MEDIA, TRUSTED }
}