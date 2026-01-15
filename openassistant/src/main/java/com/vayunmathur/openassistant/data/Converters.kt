package com.vayunmathur.openassistant.data

import androidx.room.TypeConverter
import com.vayunmathur.openassistant.ToolCall
import kotlinx.serialization.json.Json

class Converters {

    @TypeConverter
    fun fromToolCalls(toolCalls: List<ToolCall>): String {
        return Json.encodeToString(toolCalls)
    }

    @TypeConverter
    fun toToolCalls(toolCallsString: String): List<ToolCall> {
        return Json.decodeFromString(toolCallsString)
    }

    @TypeConverter
    fun toListString(list: List<String>): String {
        return Json.encodeToString(list)
    }

    @TypeConverter
    fun fromListString(listString: String): List<String> {
        return Json.decodeFromString(listString)
    }
}
