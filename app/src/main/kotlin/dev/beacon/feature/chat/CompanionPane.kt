package dev.beacon.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.beacon.core.render.ChatPart
import okhttp3.OkHttpClient

/**
 * v4 FoldAware companion artifact pane (beacon-foldable-design.md §2.3b/§2.4,
 * mockup F2): on book ≥840dp, rich content (tool cards, file previews, ⤢-expanded
 * synthetics) docks RIGHT of the thread instead of navigating fullscreen. There
 * is exactly one right-side surface and it lives inside the chat detail (§6
 * non-goals). Docking is view-state, not navigation — back never closes it first.
 */
data class DockedArtifact(
    val id: String,
    val kind: Kind,
    val title: String,
    val fileId: String = "",
    val name: String = "",
    val mime: String = "",
    val terminalServerId: String? = null,
    val terminalPath: String? = null,
    val card: ChatPart.HtmlCard? = null,
) {
    enum class Kind { File, Card }
}

/** Last-N docked artifacts per chat, in-session (§2.4: ‹ › history arrows, not tabs). */
const val DOCK_HISTORY_LIMIT = 10

/**
 * The docked pane: header `[‹ ›][title][✕ dock-close]`, body = the artifact
 * rendered full-width, footer = the artifact's own action row (the file viewer /
 * card pane render theirs internally).
 */
@Composable
fun CompanionPane(
    artifact: DockedArtifact,
    historyIndex: Int,
    historySize: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    filesApi: dev.beacon.core.api.FilesApi,
    terminalsApi: dev.beacon.core.api.TerminalsApi?,
    httpClient: OkHttpClient?,
    instanceUrl: String?,
    iframeCsp: String?,
    onPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            // Paint FIRST so the background also fills the status/nav-bar
            // insets — otherwise those strips show the (possibly night-dark)
            // window background as black borders.
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .semantics { testTag = "companion_pane" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            // §2.4: ‹ › history arrows (last 10 docked artifacts per chat).
            IconButton(onClick = onPrev, enabled = historyIndex > 0, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous artifact")
            }
            IconButton(onClick = onNext, enabled = historyIndex < historySize - 1, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next artifact")
            }
            Text(
                artifact.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp).semantics { testTag = "dock_close" }) {
                Icon(Icons.Filled.Close, contentDescription = "Close pane")
            }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (artifact.kind) {
                DockedArtifact.Kind.File -> FileViewerScreen(
                    filesApi = filesApi,
                    terminalsApi = terminalsApi,
                    fileId = artifact.fileId,
                    name = artifact.name,
                    mime = artifact.mime,
                    terminalServerId = artifact.terminalServerId,
                    terminalPath = artifact.terminalPath,
                    httpClient = httpClient,
                    instanceUrl = instanceUrl,
                    onBack = onClose,
                )
                DockedArtifact.Kind.Card -> {
                    val card = artifact.card
                    if (card != null && httpClient != null) {
                        HtmlCardPane(
                            card = card,
                            client = httpClient,
                            instanceUrl = instanceUrl,
                            iframeCsp = iframeCsp,
                            onPrompt = onPrompt,
                            fillHeight = true, // docked: the pane owns the full height, not the 60% cap
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Card unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * v4.1 split snap points (of the content width): ⅓ / ½ / ⅔. A released drag
 * snaps to the nearest point clamped into [fMin, fMax]; double-tap jumps to ½.
 * Pure so the gesture math stays unit-testable.
 */
fun snapPaneFraction(fraction: Float, fMin: Float, fMax: Float): Float {
    val points = listOf(1f / 3f, 0.5f, 2f / 3f).map { it.coerceIn(fMin, fMax) }
    val snapped = points.minByOrNull { kotlin.math.abs(it - fraction) } ?: fraction
    return snapped.coerceIn(fMin, fMax)
}

/**
 * §2.4/§4.1 user-draggable split handle: a 16dp hit area carrying a 3dp rounded
 * pill (outlineVariant — not a harsh 1dp black hairline). The host maps the
 * raw dx into the pane fraction; with the pane RIGHT of the divider, a
 * rightward drag (dx>0) shrinks the pane. [onDragEnd] fires on release (the
 * host snaps), [onDoubleTap] toggles the 50/50 split.
 */
@Composable
fun PaneDivider(
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(16.dp)
            .fillMaxHeight()
            // The hit area must paint the theme background itself — it is
            // transparent by default and would otherwise show the window
            // background (black in night mode) as a full-height split line.
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            },
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.5.dp)),
        )
    }
}

