package dev.beacon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.beacon.feature.settings.HeadersEditorScreen
import dev.beacon.feature.settings.HeadersViewModel
import dev.beacon.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** §16.4: the headers editor captured in both schemes before the UI commit. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HeadersEditorScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val populated = HeadersViewModel.UiState(
        drafts = listOf(
            HeadersViewModel.Draft(id = 1L, name = "X-Org-Routing", value = "acme"),
            HeadersViewModel.Draft(id = 2L, name = "Cookie", value = "cf_access_token=abc123"),
        ),
    )
    private val withError = HeadersViewModel.UiState(
        drafts = listOf(
            HeadersViewModel.Draft(id = 1L, name = "X-Org-Routing", value = "acme"),
            HeadersViewModel.Draft(id = 2L, name = "Authorization", value = "Bearer x"),
        ),
        errors = mapOf(2L to "Authorization is managed by sign-in"),
    )

    @Test
    fun headers_editor_light() {
        render { editor(populated) }
        capture("Headers/headers_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun headers_editor_dark() {
        render { editor(populated) }
        capture("Headers/headers_dark")
    }

    @Test
    fun headers_editor_error_light() {
        render { editor(withError) }
        capture("Headers/headers_error_light")
    }

    @Composable
    private fun editor(state: HeadersViewModel.UiState) = HeadersEditorScreen(
        state = state,
        onSave = {},
        onAdd = {},
        onRemove = {},
        onName = { _, _ -> },
        onValue = { _, _ -> },
        onBack = {},
    )

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { BeaconTheme { content() } }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
