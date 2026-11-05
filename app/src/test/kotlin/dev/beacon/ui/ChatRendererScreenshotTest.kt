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

    /** The detail-group row (web ConsecutiveDetailsGroup parity): Explored line. */
    @Test
    fun detailGroup_light() {
        render {
            dev.beacon.feature.chat.PartsView(
                parts = listOf(
                    dev.beacon.core.render.ChatPart.Reasoning("Inspecting the pricing sources first."),
                    dev.beacon.core.render.ChatPart.ToolCall(
                        name = "render_frontier_ui",
                        args = "{}",
                        status = dev.beacon.core.render.ToolStatus.Done,
                    ),
                ),
            )
        }
        capture("Chat/detail_group_light")
    }

    /** The running look: the spinner + Exploring. */
    @Test
    fun detailGroupExploring_dark() {
        render {
            dev.beacon.feature.chat.PartsView(
                parts = listOf(
                    dev.beacon.core.render.ChatPart.ToolCall(
                        name = "run_tools_parallel",
                        args = "{}",
                        status = dev.beacon.core.render.ToolStatus.Running,
                    ),
                ),
            )
        }
        capture("Chat/detail_group_exploring_dark")
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent { BeaconTheme { content() } }
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}