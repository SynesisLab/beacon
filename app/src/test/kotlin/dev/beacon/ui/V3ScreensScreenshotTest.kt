package dev.beacon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.feature.home.BeaconSidebar
import dev.beacon.feature.home.ChatsHomeActions
import dev.beacon.feature.home.ChatsHomeScreen
import dev.beacon.feature.home.ChatsHomeViewModel
import dev.beacon.feature.home.SidebarActions
import dev.beacon.feature.home.SidebarState
import dev.beacon.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §16.4: the v3 sidebar surfaces captured before the UI commit — the slide-in
 * sidebar (gated + gate-absent), the Chats home; light + dark.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class V3ScreensScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val chats = listOf(
        ChatEntity(chatId = "c1", title = "Beacon UI audit", updatedAt = now, pinned = true),
        ChatEntity(chatId = "c2", title = "GLM Flash vs Local LLMs", updatedAt = now - 3_600_000),
        ChatEntity(chatId = "c3", title = "frontier card for Tokyo weather", updatedAt = now - 86_400_000),
        ChatEntity(chatId = "c4", title = "Pixel Fold Screen Protector", updatedAt = now - 5 * 86_400_000L),
    )
    private val folders = listOf(
        dev.beacon.core.api.FolderRow(id = "f1", name = "owui-android", unread_count = 12),
        dev.beacon.core.api.FolderRow(id = "f2", name = "FRIO lore"),
    )

    @Test
    fun sidebar_open_light() {
        render {
            BeaconSidebar(
                state = SidebarState(
                    chats = chats,
                    folders = folders,
                    memoriesGate = true,
                    channelsGate = true,
                    userName = "Anthony Chen",
                    userEmail = "gamertonystuck@gmail.com",
                    userRole = "admin",
                    serverVersion = "0.13.0",
                    activeRow = "chats",
                ),
                actions = SidebarActions(),
            )
        }
        capture("V3/sidebar_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun sidebar_open_dark() {
        render {
            BeaconSidebar(
                state = SidebarState(
                    chats = chats,
                    folders = folders,
                    memoriesGate = true,
                    channelsGate = true,
                    userName = "Anthony Chen",
                    userRole = "admin",
                    serverVersion = "0.13.0",
                    activeRow = "notes",
                ),
                actions = SidebarActions(),
            )
        }
        capture("V3/sidebar_dark")
    }

    @Test
    fun sidebar_gated_off_light() {
        render { BeaconSidebar(SidebarState(chats = chats, folders = folders), SidebarActions()) }
        capture("V3/sidebar_gated_off_light")
    }

    @Test
    fun chats_home_light() {
        render { ChatsHomeScreen(ChatsHomeViewModel.UiState(chats = chats, folders = folders), ChatsHomeActions()) }
        captureWithGlyphDriftTolerance("V3/chats_home_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun chats_home_dark() {
        render { ChatsHomeScreen(ChatsHomeViewModel.UiState(chats = chats, folders = folders), ChatsHomeActions()) }
        captureWithGlyphDriftTolerance("V3/chats_home_dark")
    }

    @Test
    fun chat_loading_light() {
        // An uncached OLD chat opened: tree still on the wire → the loading
        // state, NOT the landing hero (user report 2026-11-06).
        render {
            dev.beacon.feature.chat.ChatScreen(
                dev.beacon.feature.chat.ChatViewModel.UiState(
                    engine = dev.beacon.core.chat.ChatEngine.ChatUiModel(
                        chatId = "c2",
                        title = "GLM Flash vs Local LLMs",
                        loading = true,
                    ),
                    models = emptyList(),
                ),
                dev.beacon.feature.chat.ChatActions(
                    onDraft = {}, onSend = {}, onStop = {}, onOpenModelPicker = {},
                    onOpenFile = { _, _, _, _, _ -> },
                ),
                showModelPicker = false,
                onDismissPicker = {},
                onPickModel = {},
            )
        }
        capture("V3/chat_loading_light")
    }

    @Test
    fun sidebar_hidden_rows_light() {
        // Settings → Sidebar toggles (2026-11-06): hidden rows VANISH like
        // gated rows — Notes/Files/Workspace/Settings off; Memories gated ON
        // but toggled off; Channels gated on and visible.
        render {
            BeaconSidebar(
                state = SidebarState(
                    chats = chats,
                    folders = folders,
                    memoriesGate = true,
                    channelsGate = true,
                    userName = "Anthony Chen",
                    userRole = "admin",
                    serverVersion = "0.13.0",
                    activeRow = "chats",
                    hiddenRows = setOf("notes", "files", "workspace", "memories", "settings"),
                ),
                actions = SidebarActions(),
            )
        }
        capture("V3/sidebar_hidden_rows_light")
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { BeaconTheme { content() } }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /**
     * Robolectric's native rasterizer renders the Chats-home symbol glyphs
     * (✦ / ▸ / the ⌄ chevron) with run-to-run ANTI-ALIAS drift — the same
     * test differs from itself by a few hundred soft-gray pixels per capture
     * (measured 2026-11-06: ~492 px on 411×891, all inside the glyph tiles).
     * Structural regressions are orders of magnitude above 1% of the canvas,
     * so these two goldens tolerate ≤1% differing pixels.
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
