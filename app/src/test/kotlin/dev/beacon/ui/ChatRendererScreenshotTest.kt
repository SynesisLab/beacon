package dev.beacon.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.beacon.feature.chat.MiniMarkdown
import dev.beacon.ui.theme.BeaconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures for the M1 transcript renderer: stream caret, code blocks, dark. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ChatRendererScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val streamingBody = "Reasoning about the request.\n\nAnd here is some **bold** and `inline` code.\n\n```kotlin\nfun main() {\n    println(\"hi\")\n}"
    private val settledBody = "Done. Two blocks:\n\n```json\n{\"a\": 1}\n```"

    @Test
    fun streamingRenderer_light() {
        render { MiniMarkdown(streamingBody, streaming = true) }
        capture("Chat/streaming_light")
    }

    @Test
    @Config(qualifiers = "+night")
    fun streamingRenderer_dark() {
        render { MiniMarkdown(streamingBody, streaming = true) }
        capture("Chat/streaming_dark")
    }

    @Test
    fun settledRenderer_light() {
        render { MiniMarkdown(settledBody) }
        capture("Chat/settled_light")
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { BeaconTheme { content() } }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}