package dev.beacon.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The boot cache codec (2026-11-06): chip csv + model-names json lookup. */
class DefaultsCacheTest {

    @Test
    fun chipCsvRoundTrip() {
        assertEquals(setOf("web", "code"), parseChipCsv("web,code"))
        assertEquals(emptySet<String>(), parseChipCsv(null))
        assertEquals(emptySet<String>(), parseChipCsv(""))
        assertEquals("web,code", chipsCsv(listOf("web", "code")))
    }

    @Test
    fun modelNameLookupFromCache() {
        val json = Json { encodeDefaults = true }.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("scarlet" to JsonPrimitive("Scarlet"))),
        )
        val settings = ServerSettings(modelNamesJson = json)
        assertEquals("Scarlet", settings.modelNameFor("scarlet"))
        assertNull(settings.modelNameFor("iris"))
        assertNull(settings.modelNameFor(null))
        assertNull(ServerSettings().modelNameFor("scarlet"))
        assertFalse(settings.modelNameFor("scarlet").isNullOrBlank())
    }
}
