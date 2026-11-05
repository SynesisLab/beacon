package dev.beacon.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure editor rules: per-row messages, no crash on bad rows, ordered map out. */
class HeadersEditorStateTest {

    private fun draft(id: Long, name: String = "", value: String = "") =
        HeadersViewModel.Draft(id = id, name = name, value = value)

    @Test
    fun blankNameIsRequired() {
        val errors = HeadersEditorState.validate(listOf(draft(1)))
        assertEquals("Required", errors[1])
    }

    @Test
    fun invalidNameCharacters() {
        val errors = HeadersEditorState.validate(listOf(draft(1, name = "X Org", value = "v")))
        assertEquals("Letters, digits and - . _ ~ only", errors[1])
    }

    @Test
    fun reservedAuthorizationCannotBeSet() {
        val errors = HeadersEditorState.validate(listOf(draft(1, name = "Authorization", value = "Bearer x")))
        assertEquals("Authorization is managed by sign-in", errors[1])
    }

    @Test
    fun duplicateNamesCaseInsensitive() {
        val errors = HeadersEditorState.validate(
            listOf(draft(1, name = "X-Key", value = "a"), draft(2, name = "x-key", value = "b")),
        )
        assertEquals(mapOf(2L to "Already added"), errors)
    }

    @Test
    fun blankValueIsRequired() {
        val errors = HeadersEditorState.validate(listOf(draft(1, name = "X-Key")))
        assertEquals("Required", errors[1])
    }

    @Test
    fun firstFailingRuleWinsPerRow() {
        // name invalid AND value missing → the name rule reports.
        val errors = HeadersEditorState.validate(listOf(draft(1, name = "bad name")))
        assertEquals("Letters, digits and - . _ ~ only", errors[1])
    }

    @Test
    fun validSetYieldsNoErrors_andBuildsOrderedList() {
        val drafts = listOf(
            draft(1, name = "X-Org-Routing", value = "acme"),
            draft(2, name = "Cookie", value = "cf=abc"),
        )
        assertEquals(emptyMap<Long, String>(), HeadersEditorState.validate(drafts))
        val headers = HeadersEditorState.toHeaders(drafts)
        assertEquals(listOf("X-Org-Routing", "Cookie"), headers.keys.toList())
        assertEquals("acme", headers["X-Org-Routing"])
    }

    @Test
    fun rowValidationDoesNotBlockOtherGoodRows() {
        val errors = HeadersEditorState.validate(
            listOf(draft(1, name = "X-Ok", value = "v"), draft(2, name = "X Bad", value = "v")),
        )
        assertEquals(mapOf(2L to "Letters, digits and - . _ ~ only"), errors)
    }
}
