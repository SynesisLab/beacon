package dev.beacon.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class BeaconConverters {
    private val json = Json

    @androidx.room.TypeConverter
    fun listToJson(value: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), value)

    @androidx.room.TypeConverter
    fun jsonToList(value: String): List<String> =
        try {
            json.decodeFromString(ListSerializer(String.serializer()), value)
        } catch (_: Exception) {
            emptyList()
        }

    @androidx.room.TypeConverter
    fun mapToJson(value: Map<String, String>): String =
        json.encodeToString(MapSerializer(String.serializer(), String.serializer()), value)

    @androidx.room.TypeConverter
    fun jsonToMap(value: String): Map<String, String> =
        try {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), value)
        } catch (_: Exception) {
            emptyMap()
        }
}

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(BeaconConverters::class)
abstract class BeaconDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    abstract fun messages(): MessageDao
}