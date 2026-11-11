package dev.beacon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.beacon.core.chat.ChatEngine
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.feature.chat.ChatActions
import dev.beacon.feature.chat.ChatScreen
import dev.beacon.feature.chat.ChatViewModel
import dev.beacon.feature.chat.CompanionPane
import dev.beacon.feature.chat.DockedArtifact
import dev.beacon.feature.chat.PaneDivider
import dev.beacon.feature.home.ChatsHomeActions
import dev.beacon.feature.home.ChatsHomeScreen
import dev.beacon.feature.home.ChatsHomeViewModel
import dev.beacon.feature.home.FoldOcclusion
import dev.beacon.feature.home.FoldOrientation
import dev.beacon.feature.home.FoldSpec
import dev.beacon.feature.home.FoldState
import dev.beacon.feature.home.HingeBand
import dev.beacon.feature.home.HingeGap
import dev.beacon.feature.home.IconRail
import dev.beacon.feature.home.PostureState
import dev.beacon.feature.home.RailActions
import dev.beacon.feature.home.SidebarState
import dev.beacon.feature.home.postureState
import dev.beacon.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v4.0 FoldAware goldens (beacon-foldable-design.md §4.6): book rail+thread,
 * book docked companion pane, book on a vertical FULL hinge, tabletop chat,
 * tabletop home, dark book. Phone-mode goldens live in M0/V3 and are unchanged.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h700dp")
class V4FoldableScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val chats = listOf(
        ChatEntity(chatId = "c1", title = "Beacon UI audit", updatedAt = now, pinned = true),
        ChatEntity(chatId = "c2", title = "FoldAware expansion", updatedAt = now - 3_600_000),
        ChatEntity(chatId = "c3", title = "SCHD drip plan", updatedAt = now - 86_400_000),
    )
    private val sidebarState = SidebarState(
        chats = chats,
        folders = listOf(dev.beacon.core.api.FolderRow(id = "f1", name = "owui-android", unread_count = 12)),
        memoriesGate = true,
        channelsGate = true,
        userName = "Anthony Chen",
        userRole = "admin",
        serverVersion = "0.13.0",
        activeRow = "chats",
    )
    private val chatState = ChatViewModel.UiState(
        engine = ChatEngine.ChatUiModel(
            chatId = "c1",
            title = "Beacon UI audit",
            messages = listOf(
                ChatEngine.RenderMessage("m1", "user", markdown = "unfold it — rail, not a full pane"),
                ChatEngine.RenderMessage("m2", "assistant", markdown = "Book mode: icon rail 72dp / thread gets the rest, fixed at every width. Chats live in the rail's popovers — the thread owns the rest."),
            ),
        ),
        models = emptyList(),
    )
    private val chatActions = ChatActions(
        onDraft = {}, onSend = {}, onStop = {}, onOpenModelPicker = {},
        onOpenFile = { _, _, _, _, _ -> },
    )

    // Robolectric captures at 1x density (w900dp → 900px), so px == dp here.
    private val bookWide = postureState(windowWidthDp = 900, windowWidthPx = 900, windowHeightPx = 700, fold = null)
    private val tabletop = postureState(
        windowWidthDp = 700,
        windowWidthPx = 700,
        windowHeightPx = 560,
        fold = FoldSpec(FoldState.HALF_OPENED, FoldOrientation.HORIZONTAL, FoldOcclusion.FULL, dev.beacon.feature.home.FoldBounds(0, 300, 700, 330)),
    )

    @Test
    fun book_rail_thread_light() {
        render {
            Row(Modifier.fillMaxSize()) {
                IconRail(state = sidebarState, actions = RailActions())
                Box(Modifier.weight(1f).fillMaxSize()) {
                    ChatScreen(state = chatState, actions = chatActions, showModelPicker = false, onDismissPicker = {}, onPickModel = {}, posture = bookWide)
                }
            }
        }
        capture("V4/book_rail_thread_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun book_rail_thread_dark() {
        render {
            Row(Modifier.fillMaxSize()) {
                IconRail(state = sidebarState, actions = RailActions())
                Box(Modifier.weight(1f).fillMaxSize()) {
                    ChatScreen(state = chatState, actions = chatActions, showModelPicker = false, onDismissPicker = {}, onPickModel = {}, posture = bookWide)
                }
            }
        }
        capture("V4/book_rail_thread_dark")
    }

    @Test
    fun book_companion_docked_light() {
        // v4.1: the rail is RESIDENT while docked — frame = rail | thread |
        // divider | companion pane, all inside the window.
        val artifact = DockedArtifact(
            id = "card:chart1",
            kind = DockedArtifact.Kind.Card,
            title = "chart.svg",
            card = null,
        )
        render {
            Row(Modifier.fillMaxSize()) {
                IconRail(state = sidebarState, actions = RailActions())
                Box(Modifier.weight(0.55f).fillMaxSize()) {
                    ChatScreen(state = chatState, actions = chatActions, showModelPicker = false, onDismissPicker = {}, onPickModel = {}, posture = bookWide)
                }
                PaneDivider(onDragDelta = {})
                Box(Modifier.weight(0.45f).fillMaxSize()) {
                    CompanionPane(
                        artifact = artifact,
                        historyIndex = 0,
                        historySize = 1,
                        onPrev = {}, onNext = {}, onClose = {},
                        filesApi = dev.beacon.core.api.FilesApi(
                            okhttp3.OkHttpClient(),
                            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                            dev.beacon.core.api.InstanceSource { "https://example.invalid" },
                        ),
                        terminalsApi = null,
                        httpClient = null,
                        instanceUrl = null,
                        iframeCsp = null,
                        onPrompt = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        capture("V4/book_companion_docked_light")
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun book_vertical_hinge_full_light() {
        // §1: half-opened VERTICAL hinge → book with the hinge as a hard divider
        // band between the rail and the content pane (occlusion FULL).
        val band = HingeBand(
            topPanePx = 0, gapPx = 0, bottomPanePx = 0,
            leftPx = 144, widthPx = 40, full = true,
            orientation = FoldOrientation.VERTICAL,
        )
        render {
            Row(Modifier.fillMaxSize()) {
                IconRail(state = sidebarState, actions = RailActions())
                HingeGap(band)
                Box(Modifier.weight(1f).fillMaxSize()) {
                    ChatScreen(state = chatState, actions = chatActions, showModelPicker = false, onDismissPicker = {}, onPickModel = {}, posture = bookWide)
                }
            }
        }
        capture("V4/book_vertical_hinge_full_light")
    }

    @Test
    @Config(qualifiers = "w700dp-h560dp")
    fun tabletop_chat_light() {
        render {
            ChatScreen(state = chatState, actions = chatActions, showModelPicker = false, onDismissPicker = {}, onPickModel = {}, posture = tabletop)
        }
        capture("V4/tabletop_chat_light")
    }

    @Test
    @Config(qualifiers = "w700dp-h560dp")
    fun tabletop_home_light() {
        render {
            ChatsHomeScreen(
                state = ChatsHomeViewModel.UiState(chats = chats),
                actions = ChatsHomeActions(),
                posture = tabletop,
            )
        }
        captureWithGlyphDriftTolerance("V4/tabletop_home_light")
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { BeaconTheme { content() } }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /**
     * Same ≤1% glyph-drift tolerance as V3's chats_home goldens: the pinned ✦
     * row's symbol glyph anti-aliases with run-to-run drift under Robolectric's
     * native rasterizer; structural regressions are orders of magnitude larger.
     */
    private fun captureWithGlyphDriftTolerance(name: String) {
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/$name.png",
            roborazziOptions = com.github.takahirom.roborazzi.RoborazziOptions(
                compareOptions = com.github.takahirom.roborazzi.RoborazziOptions.CompareOptions(
                    resultValidator = { result -> result.pixelDifferences * 100 <= result.pixelCount },
                ),
            ),
        )
    }
}
