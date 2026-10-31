package com.vayunmathur.todo.data

import androidx.room3.ColumnTypeConverter
import kotlinx.serialization.json.Json

class TodoConverters {
    @ColumnTypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @ColumnTypeConverter
    fun toStringList(value: String): List<String> =
        runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}
