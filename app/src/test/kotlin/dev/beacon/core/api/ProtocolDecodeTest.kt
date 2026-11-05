package dev.beacon.core.api

import dev.beacon.core.api.dto.AppConfig
import dev.beacon.core.api.dto.ModelsPage
import dev.beacon.core.api.dto.SignInData
import dev.beacon.core.api.dto.VersionInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tolerant-decode fixtures shaped exactly like the server payloads verified in
 * docs/protocol-notes.md. Unknown keys MUST be ignored (schema velocity belongs
 * to the server); missing optional keys must not break decode.
 */
class ProtocolDecodeTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // GET /api/version — main.py:2701-2706
    @Test
    fun version_decodes() {
        val info = json.decodeFromString<VersionInfo>(
            """{"version": "0.7.2", "deployment_id": "abc123", "extra_unknown": true}""",
        )
        assertEquals("0.7.2", info.version)
        assertEquals("abc123", info.deployment_id)
    }

    // GET /api/config — main.py:2289-2472 (subset fields; unknown keys tolerated)
    @Test
    fun config_decodesWithFeatureGates() {
        val config = json.decodeFromString<AppConfig>(
            """
            {
              "status": true,
              "name": "Self-Hosted UI",
              "version": "0.7.2",
              "oauth": {"providers": {"github": {"name": "GitHub"}}, "auto_redirect": false},
              "features": {
                "auth": true,
                "enable_login_form": true,
                "enable_ldap": false,
                "enable_websocket": true,
                "enable_api_keys": true,
                "enable_folders": true,
                "enable_channels": false,
                "enable_notes": true,
                "enable_web_search": true,
                "enable_title_generation": true,
                "future_gates_and_more": [1, 2, 3],
                "models": [{"id": "irrelevant"}]
              },
              "default_models": "llama-3.1:70b",
              "ui": {
                "default_interface_settings": {},
                "iframe_csp": "default-src 'self'",
                "unrelated_future_key": true
              }
            }
            """.trimIndent(),
        )
        assertEquals("Self-Hosted UI", config.name)
        assertTrue(config.features!!.enable_folders == true)
        assertTrue(config.features!!.enable_channels == false)
        assertEquals("llama-3.1:70b", config.default_models)
        // ui.iframe_csp — stores/index.ts:437-446 (§7.2 card CSP, admin-configured)
        assertEquals("default-src 'self'", config.ui?.iframe_csp)
    }

    // POST /api/v1/auths/signin — models/auths.py:96-133
    @Test
    fun signin_successResponse() {
        val data = json.decodeFromString<SignInData>(
            """
            {
              "token": "eyJhbGciOi…",
              "token_type": "bearer",
              "id": "user-1", "name": "You", "email": "you@example.com",
              "role": "user", "profile_image_url": "/api/v1/files/user-1/profile.png",
              "permissions": {}, "expires_at": 1234567890
            }
            """.trimIndent(),
        )
        assertTrue(data.isTokenSignIn)
        assertEquals("You", data.name)
        assertEquals("bearer", data.token_type)
    }

    @Test
    fun signin_mfaChallengeResponse() {
        val data = json.decodeFromString<SignInData>(
            """{"next_step": "verify", "challenge_token": "c-1", "expires_in": 300}""",
        )
        assertTrue(data.isMfaChallenge)
        assertTrue(!data.isTokenSignIn)
    }

    @Test
    fun signin_pendingUserResponse() {
        val data = json.decodeFromString<SignInData>("""{"next_step": "pending"}""")
        assertTrue(data.isPendingUser)
        assertTrue(!data.isMfaChallenge)
    }

    // GET /api/v1/models/list — routers/models.py:227-239, models/models.py:237-240
    @Test
    fun modelsList_decodes() {
        val page = json.decodeFromString<ModelsPage>(
            """
            {
              "items": [
                {"id": "gpt-4o", "name": "GPT-4o", "info": {"meta": {"hidden": false}}},
                {"id": "llama-3.1:70b", "name": "Llama 3.1 70B", "created_at": "01-01-2025"},
                {"id": "gpt-4o", "name": "GPT-4o (dupe entry)"}
              ],
              "total": 2,
              "page": 1, "page_items_count": 30
            }
            """.trimIndent(),
        )
        assertEquals(3, page.items.size)
        assertEquals(2, page.total)
        // The client-visible models list de-duplicates on id (OwuiApi.models()).
        assertEquals(2, page.copy(items = page.items.distinctBy { it.id }).items.size)
    }
}