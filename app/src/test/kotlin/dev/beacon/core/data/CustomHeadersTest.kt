package dev.beacon.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The M4 headers editor's rules: token names, injection-proof values, reserved auth, blob codec. */
class CustomHeadersTest {

    @Test
    fun tokenNamesAccepted_table() {
        val valid = listOf(
            "X-Org-Routing", "x", "X1", "X_API_KEY", "X.Y", "X+Y", "x~y", "x`y", "x!y",
            "x#y", "x\$y", "x%y", "x&y", "x*y", "x'y",
        )
        valid.forEach { assertTrue("should accept: $it", CustomHeaders.isValidName(it)) }
    }

    @Test
    fun nonTokenNamesRejected_table() {
        val invalid = listOf("", " ", "X Org", "X:Org", "X;Org", "X=Org", "X=Org", "中文-hdr", "X\"Y", "X\ty")
        invalid.forEach { assertFalse("should reject: $it", CustomHeaders.isValidName(it)) }
    }

    @Test
    fun lineBreaksAndNulRejectedInValues() {
        assertFalse(CustomHeaders.isValidValue("a\nb"))
        assertFalse(CustomHeaders.isValidValue("a\rb"))
        assertFalse(CustomHeaders.isValidValue("\u0000"))
        // Tab is legal inside a field-value; any printable text is fine.
        assertTrue(CustomHeaders.isValidValue("a\tb"))
        assertTrue(CustomHeaders.isValidValue("cf_access=abc123"))
        // Empty is rejected — the editor marks it Required (HTTP would allow it; pointless).
        assertFalse(CustomHeaders.isValidValue(""))
    }

    @Test
    fun authorizationIsReservedAnyCase() {
        assertTrue(CustomHeaders.isReserved("Authorization"))
        assertTrue(CustomHeaders.isReserved("authorization"))
        assertTrue(CustomHeaders.isReserved("AUTHORIZATION"))
        assertFalse(CustomHeaders.isReserved("X-Authorization"))
        assertFalse(CustomHeaders.isReserved("Cookie"))
    }

    @Test
    fun sanitizeDropsInvalidAndReserved_trimsParts() {
        val out = CustomHeaders.sanitize(
            linkedMapOf(
                " X-Org-Routing " to " acme ",
                "authorization" to "Bearer evil",
                "X-Bad Name" to "v",
                "X-Crlf" to "a\nb",
                "" to "v",
                "X-Empty" to "",
                "  X-Keep  " to "  keep  ",
            ),
        )
        assertEquals(listOf("X-Org-Routing", "X-Keep"), out.keys.toList())
        assertEquals("acme", out["X-Org-Routing"])
        assertEquals("keep", out["X-Keep"])
    }

    @Test
    fun blobRoundTrip_preservesOrder() {
        val map = linkedMapOf("X-First" to "1", "X-Second" to "2", "Cookie" to "cf=abc")
        val decoded = CustomHeaders.decode(CustomHeaders.encode(map))
        assertEquals(map, decoded)
        assertEquals(map.keys.toList(), decoded.keys.toList())
    }

    @Test
    fun decodeIsDefensiveOnGarbage() {
        assertEquals(emptyMap<String, String>(), CustomHeaders.decode(null))
        assertEquals(emptyMap<String, String>(), CustomHeaders.decode(""))
        assertEquals(emptyMap<String, String>(), CustomHeaders.decode("not json"))
        assertEquals(emptyMap<String, String>(), CustomHeaders.decode("{\"a\":1}"))
        // A hand-edited blob still runs the sanitizer.
        assertEquals(emptyMap<String, String>(), CustomHeaders.decode("{\"authorization\":\"x\"}"))
        assertEquals(mapOf("X-Ok" to "v"), CustomHeaders.decode("{\"X-Ok\":\"v\"}"))
    }
}
