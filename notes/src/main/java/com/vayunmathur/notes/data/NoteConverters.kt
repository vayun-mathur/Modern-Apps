package com.vayunmathur.notes.data

import androidx.room3.ColumnTypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Room column converters for [Note]. [Note.tags] is stored as one TEXT column
 * holding a JSON array of strings (`["work","home"]`, `[]` when untagged).
 * A tag table would need joins on every list query and a second sync surface;
 * at <= 20 short tags per note a JSON column is simpler and indexes nothing we
 * query by anyway (filtering happens in memory over the loaded list).
 */
class NoteConverters {
    @ColumnTypeConverter
    fun fromTags(value: List<String>): String = Json.encodeToString(value)

    /** Tolerant: a malformed value reads as untagged instead of failing the whole query. */
    @ColumnTypeConverter
    fun toTags(value: String): List<String> = try {
        Json.decodeFromString<List<String>>(value)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}
