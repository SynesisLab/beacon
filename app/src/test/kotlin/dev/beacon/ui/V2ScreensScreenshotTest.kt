package dev.beacon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.beacon.feature.home.ChatsHomeActions
import dev.beacon.feature.home.ChatsHomeScreen
import dev.beacon.feature.home.ChatsHomeViewModel
import dev.beacon.feature.home.MoreScreen
import dev.beacon.feature.home.MoreViewModel
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * §16.4: the v2 tab surfaces captured before the UI commit — Chats home
 * (sections/rows/kebabs + tab bar) and More (gated list + footer).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class V2ScreensScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val home = ChatsHomeViewModel.UiState(
        chats = listOf(
            ChatEntity(chatId = "c1", title = "Beacon UI audit", updatedAt = now, pinned = true),
            ChatEntity(chatId = "c2", title = "GLM Flash vs Local LLMs", updatedAt = now - 3_600_000),
            ChatEntity(chatId = "c3", title = "frontier card for Tokyo weather", updatedAt = now - 86_400_000),
            ChatEntity(chatId = "c4", title = "Pixel Fold Screen Protector", updatedAt = now - 5 * 86_400_000L),
        ),
        folders = listOf(
            dev.beacon.core.api.FolderRow(id = "f1", name = "owui-android"),
            dev.beacon.core.api.FolderRow(id = "f2", name = "FRIO lore", unread_count = 12),
        ),
    )
    private val more = MoreViewModel.UiState(
        memoriesGate = true,
        channelsGate = true,
        serverVersion = "0.13.0",
    )

    @Test
    fun chats_home_light() {
        render { ChatsHomeScreen(home, ChatsHomeActions(), tabState = { dev.beacon.feature.chat.BeaconTabBar("chats", onSelect = {}) }) }
        capture("V2/chats_home_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun chats_home_dark() {
        render { ChatsHomeScreen(home, ChatsHomeActions(), tabState = { dev.beacon.feature.chat.BeaconTabBar("chats", onSelect = {}) }) }
        capture("V2/chats_home_dark")
    }

    @Test
    fun more_light() {
        render { MoreScreen(more, onOpenSurface = {}, onOpenSettings = {}, tabState = { dev.beacon.feature.chat.BeaconTabBar("more", onSelect = {}) }) }
        capture("V2/more_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun more_dark() {
        render { MoreScreen(more, onOpenSurface = {}, onOpenSettings = {}, tabState = { dev.beacon.feature.chat.BeaconTabBar("more", onSelect = {}) }) }
        capture("V2/more_dark")
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { BeaconTheme { content() } }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
