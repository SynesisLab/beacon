package dev.beacon.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent.md §11 rule exercised edge-on: credentials never leave the instance origin. */
class InterceptorRulesTest {
    private val instance = "https://ai.example.com"

    private fun request(url: String) = Request.Builder().url(url.toHttpUrl()).build()

    @Test
    fun sameOriginRequestGetsBearer() {
        val result = request("$instance/api/v1/models/list?page=1")
            .attachInstanceAuth(instance, "sk-abc", emptyMap())
        assertEquals("Bearer sk-abc", result.header("Authorization"))
    }

    @Test
    fun crossOriginRequestNeverGetsCredentials() {
        val result = request("https://tracker.evil.example.com/collect")
            .attachInstanceAuth(instance, "sk-abc", emptyMap())
        assertNull(result.header("Authorization"))
    }

    @Test
    fun differentPortIsDifferentOrigin() {
        val result = request("https://ai.example.com:8443/api/v1/models/list")
            .attachInstanceAuth(instance, "sk-abc", emptyMap())
        assertNull(result.header("Authorization"))
    }

    @Test
    fun customHeadersAttachOnlySameOrigin() {
        val result = request("$instance/api/config")
            .attachInstanceAuth(instance, null, mapOf("X-Org-Routing" to "acme", " " to "skipped"))
        assertEquals("acme", result.header("X-Org-Routing"))
        assertNull(result.header(" "))
    }

    @Test
    fun customHeadersCannotOverrideBearerAuthorization() {
        // Conduit parity: the credential Beacon manages is never overridable.
        val result = request("$instance/api/config")
            .attachInstanceAuth(
                instance,
                "sk-abc",
                mapOf("authorization" to "Bearer evil", "AUTHORIZATION" to "also-evil"),
            )
        assertEquals("Bearer sk-abc", result.header("Authorization"))
    }

    @Test
    fun injectionHeadersWithLineBreaksAreDropped() {
        // OkHttp throws on CR/LF in a name or value — the gate must drop them first.
        val result = request("$instance/api/config")
            .attachInstanceAuth(
                instance,
                null,
                mapOf("X-Evil\nInjected" to "v", "X-Evil" to "a\nb", "X-Ok" to "v"),
            )
        assertNull(result.header("X-Evil\nInjected"))
        assertNull(result.header("X-Evil"))
        assertEquals("v", result.header("X-Ok"))
    }

    @Test
    fun cookieHeaderIsAllowedForProxyFlows() {
        val result = request("$instance/api/config")
            .attachInstanceAuth(instance, "sk-abc", mapOf("Cookie" to "cf_access=abc"))
        assertEquals("cf_access=abc", result.header("Cookie"))
    }

    @Test
    fun noInstanceMeansNoCredentials() {
        val result = request("$instance/api/config")
            .attachInstanceAuth(null, "sk-abc", emptyMap())
        assertNull(result.header("Authorization"))
    }

    @Test
    fun blankInstanceUrlNeverMatches() {
        val result = request("$instance/api/config")
            .attachInstanceAuth("", "sk-abc", emptyMap())
        assertNull(result.header("Authorization"))
    }

    @Test
    fun urlNormalizationStaysOnOrigin() {
        // Trailing-slash forms of the same origin still count as same-origin.
        val result = request("$instance/api/config")
            .attachInstanceAuth("$instance", "sk-abc", emptyMap())
        assertEquals("Bearer sk-abc", result.header("Authorization"))
    }

    @Test
    fun credentialsAreNotDoubleAttacked() {
        // A malicious subdomain of the instance cannot harvest the bearer token.
        val evil = "https://ai.example.com.attacker.com"
        val result = request("$evil/x")
            .attachInstanceAuth(instance, "sk-abc", emptyMap())
        assertFalse(result.header("Authorization") != null)
        assertTrue(result.header("Authorization") == null)
    }
}