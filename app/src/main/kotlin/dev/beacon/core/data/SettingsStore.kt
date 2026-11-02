package dev.beacon.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private val settingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * kotlinx-serialization-backed typed DataStore instead of Proto (ADR-0001):
 * one serialization stack app-wide, no protoc/protobuf plugin in the Gradle graph.
 */
object ServerSettingsSerializer : Serializer<ServerSettings> {
    override val defaultValue: ServerSettings = ServerSettings()

    override suspend fun readFrom(input: InputStream): ServerSettings =
        try {
            settingsJson.decodeFromString<ServerSettings>(input.readBytes().decodeToString())
        } catch (_: Exception) {
            // Damaged/partial file (e.g. power cut mid-write): protocol truth is server-wins;
            // settings fall back to fresh onboarding.
            defaultValue
        }

    override suspend fun writeTo(t: ServerSettings, output: OutputStream) {
        output.write(settingsJson.encodeToString(ServerSettings.serializer(), t).encodeToByteArray())
        output.flush()
    }
}

fun createSettingsDataStore(context: Context): DataStore<ServerSettings> =
    DataStoreFactory.create(
        serializer = ServerSettingsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { ServerSettings() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) {
        File(context.filesDir, "beacon/settings.json")
    }

/** Thin typed accessor used across the app. */
interface SettingsFlow {
    val settings: Flow<ServerSettings>
}