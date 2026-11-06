package dev.beacon.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What does a bottom-of-list upward drag deliver to a parent nestedScroll connection? */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PullGestureTest {
    @get:Rule val composeRule = createComposeRule()

    @Composable
    private fun Subject(events: MutableList<String>) {
        val listState = rememberLazyListState()
        val conn = object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                events.add("preScroll $available")
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): Offset {
                events.add("postScroll consumed=$consumed available=$available")
                return Offset.Zero
            }
        }
        Column(Modifier.fillMaxSize().nestedScroll(conn)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize().semantics { testTag = "list" }) {
                    items(50) { Text("item $it", modifier = Modifier.height(40.dp)) }
                }
            }
        }
    }

    @Test
    fun upwardDragAtBottom_deliversNegativeAvailable() {
        val events = mutableListOf<String>()
        composeRule.setContent { Subject(events) }
        composeRule.waitForIdle()
        // at bottom already (reverseLayout starts at index 0 = bottom)
        composeRule.onNodeWithTag("list").performTouchInput {
            // slow upward drag: down at center, move up in steps, up
            down(center)
            moveTo(center - Offset(0f, 40f))
            moveTo(center - Offset(0f, 120f))
            moveTo(center - Offset(0f, 260f))
            moveTo(center - Offset(0f, 420f))
            up()
        }
        composeRule.waitForIdle()
        val post = events.filter { it.startsWith("postScroll") }
        println("PULLTEST events: $events")
        assertTrue("expected postScroll events, got $events", post.isNotEmpty())
        assertTrue(
            "expected some negative available.y, got $post",
            post.any { "available=Offset(0.0, -" in it },
        )
    }

    @Test
    fun atBottom_canScrollForwardIsFalse() {
        val events = mutableListOf<String>()
        var state: androidx.compose.foundation.lazy.LazyListState? = null
        composeRule.setContent {
            state = rememberLazyListState()
            val conn = object : NestedScrollConnection {}
            Column(Modifier.fillMaxSize().nestedScroll(conn)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = state!!, reverseLayout = true, modifier = Modifier.fillMaxSize().semantics { testTag = "list" }) {
                        items(50) { Text("item $it", modifier = Modifier.height(40.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        println("PULLTEST atStart canScrollForward=${state!!.canScrollForward} canScrollBackward=${state!!.canScrollBackward} firstVisible=${state!!.firstVisibleItemIndex}")
        assertTrue("at the visual bottom canScrollBackward must be false (the app's atBottom check)", !state!!.canScrollBackward)
    }
}
