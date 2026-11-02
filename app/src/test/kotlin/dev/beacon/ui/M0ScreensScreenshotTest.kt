package dev.beacon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.beacon.core.api.dto.OwuiModel
import dev.beacon.core.chat.ChatEngine
import dev.beacon.core.data.AuthMethod
import dev.beacon.core.data.local.ChatEntity
import dev.beacon.feature.chat.ChatActions
import dev.beacon.feature.chat.ChatScreen
import dev.beacon.feature.chat.ChatViewModel
import dev.beacon.feature.home.ChatListActions
import dev.beacon.feature.home.HomeViewModel
import dev.beacon.feature.onboarding.OnboardingActions
import dev.beacon.feature.onboarding.OnboardingScreen
import dev.beacon.feature.onboarding.OnboardingViewModel
import dev.beacon.feature.settings.SettingsScreen
import dev.beacon.feature.settings.SettingsViewModel
import dev.beacon.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Baseline captures for the M0 screens in both color schemes (agent.md §16.4:
 * screenshots before any UI commit). Recorded once, then verified on UI changes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class M0ScreensScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val models = listOf(
        OwuiModel("gpt-4o", "GPT-4o"),
        OwuiModel("llama-3.1:70b", "Llama 3.1 70B"),
        OwuiModel("claude-sonnet-5", "Claude Sonnet 5"),
    )
    private val urlState = OnboardingViewModel.UiState(step = OnboardingViewModel.Step.Url)
    private val authState = OnboardingViewModel.UiState(
        step = OnboardingViewModel.Step.Auth,
        authMode = AuthMethod.ApiKey,
    )
    private val modelsState = OnboardingViewModel.UiState(
        step = OnboardingViewModel.Step.Models,
        models = models,
        chosenModelId = "claude-sonnet-5",
    )
    private val chats = listOf(
        ChatEntity(chatId = "c1", updatedAt = 1730000000, title = "Weekly plan"),
        ChatEntity(chatId = "c2", updatedAt = 1730000001, title = "Ideas", pinned = true),
    )
    private val home = HomeViewModel.UiState(chats = chats)
    private val settings = SettingsViewModel.UiState(
        serverUrl = "https://ai.example.com",
        serverVersion = "0.7.2",
        authMethod = AuthMethod.ApiKey,
        signedInEmail = "you@example.com",
    )
    private val chat = ChatViewModel.UiState(
        engine = ChatEngine.ChatUiModel(
            chatId = "c1",
            messages = listOf(
                ChatEngine.RenderMessage("m1", "user", markdown = "Check the build logs for me?"),
                ChatEngine.RenderMessage(
                    "m2",
                    "assistant",
                    markdown = "Sure — the relevant lines:\n\n```kotlin\nfun logs() {\n    println(logs)\n}\n```\n\nHere are the findings:\n- First item with `code`\n- Second item with **bold**",
                ),
            ),
        ),
        models = models,
        selectedModelId = "claude-sonnet-5",
    )

    private val chatActions = ChatActions(
        onDraft = {}, onSend = {}, onStop = {}, onBack = {}, onOpenModelPicker = {},
        onOpenFile = { _, _, _, _, _ -> },
    )

    private val onboardingActions = OnboardingActions(
        setUrlDraft = {}, probeServer = {}, setAuthMode = {}, setApiKeyDraft = {},
        submitApiKey = {}, setEmailDraft = {}, setPasswordDraft = {}, submitPassword = {},
        chooseModel = {}, finish = {},
    )
    private val homeActions = ChatListActions(
        onNewChat = {}, onOpenChat = {}, onRefresh = {}, onOpenSettings = {}, onOpenModels = {},
        onTogglePin = {}, onDelete = {}, onLoadNextPage = {},
    )

    @Test
    fun onboarding_url_light() {
        render { OnboardingScreen(urlState, onboardingActions) }
        capture("Onboarding/onboarding_url_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun onboarding_url_dark() {
        render { OnboardingScreen(urlState, onboardingActions) }
        capture("Onboarding/onboarding_url_dark")
    }

    @Test
    fun onboarding_auth_light() {
        render { OnboardingScreen(authState, onboardingActions) }
        capture("Onboarding/onboarding_auth_light")
    }

    @Test
    fun onboarding_models_light() {
        render { OnboardingScreen(modelsState, onboardingActions) }
        capture("Onboarding/onboarding_models_light")
    }

    @Test
    fun home_light() {
        render {
            dev.beacon.feature.home.HomeScreen(home, homeActions)
        }
        capture("Home/home_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun home_dark() {
        render {
            dev.beacon.feature.home.HomeScreen(home, homeActions)
        }
        capture("Home/home_dark")
    }

    @Test
    fun chat_bubbles_light() {
        render { ChatScreen(chat, chatActions, showModelPicker = false, onDismissPicker = {}, onPickModel = {}) }
        capture("Chat/chat_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun chat_bubbles_dark() {
        render { ChatScreen(chat, chatActions, showModelPicker = false, onDismissPicker = {}, onPickModel = {}) }
        capture("Chat/chat_dark")
    }

    @Test
    fun settings_light() {
        render { SettingsScreen(settings, onSignOut = {}, onClose = {}) }
        capture("Settings/settings_light")
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { BeaconTheme { content() } }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}