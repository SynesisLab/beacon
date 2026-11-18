package dev.beacon.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card pane's VERTICAL handoff decision ([cardScrollFollows]): the
 * document must be able to move in the DRAG'S DIRECTION or the gesture
 * passes to the chat list. Finger moving down reveals earlier doc content
 * (up-capacity); moving up reveals later content (down-capacity).
 *
 * The 0.3.2 regression this pins: BOTH-edges-dead handoff left a document
 * at its bottom eating upward drags — "can't scroll down to see more".
 */
class CardScrollHandoffTest {

    @Test
    fun `finger down mid-document follows - the card scrolls`() {
        assertTrue(cardScrollFollows(12f, canScrollUp = true, canScrollDown = true))
    }

    @Test
    fun `finger down at document top hands off to the chat list`() {
        // scrollTop=0: dragging down can expose nothing; the page must take over.
        assertFalse(cardScrollFollows(12f, canScrollUp = false, canScrollDown = true))
    }

    @Test
    fun `finger up at document bottom hands off to the chat list`() {
        // THE report: scrolled to the doc's bottom, upward drags were eaten
        // and the chat never advanced.
        assertFalse(cardScrollFollows(-12f, canScrollUp = true, canScrollDown = false))
    }

    @Test
    fun `finger up mid-document follows - the card scrolls`() {
        assertTrue(cardScrollFollows(-12f, canScrollUp = true, canScrollDown = true))
    }

    @Test
    fun `document fits its box - both directions hand off`() {
        assertFalse(cardScrollFollows(30f, canScrollUp = false, canScrollDown = false))
        assertFalse(cardScrollFollows(-30f, canScrollUp = false, canScrollDown = false))
    }

    @Test
    fun `sub-pixel wiggle stays with the card`() {
        assertTrue(cardScrollFollows(0.2f, canScrollUp = false, canScrollDown = false))
        assertTrue(cardScrollFollows(-0.4f, canScrollUp = true, canScrollDown = true))
        assertTrue(cardScrollFollows(0f, canScrollUp = false, canScrollDown = true))
    }

    @Test
    fun `half-pixel boundary counts as wiggle`() {
        // Strictly beyond 0.5px locks a direction.
        assertTrue(cardScrollFollows(0.5f, canScrollUp = false, canScrollDown = true))
        assertTrue(cardScrollFollows(-0.5f, canScrollUp = true, canScrollDown = false))
    }
}
