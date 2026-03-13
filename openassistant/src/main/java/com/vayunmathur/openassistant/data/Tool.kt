package com.vayunmathur.openassistant.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class ToolResult(val llmResponse: String, val userResponse: String)

@Serializable
data class ToolSimple(
    val name: String,
    val description: String,
    val params: List<Parameter>,
    @Transient
    val action: ToolFunctionType = { _, _ -> ToolResult("", "") },
) {
    @Serializable
    data class Parameter(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean,
    )

    fun systemDescription(): String {
        return Json.encodeToString(this)
    }
}

fun stringParam(name: String, description: String, required: Boolean = true): ToolSimple.Parameter {
    return ToolSimple.Parameter(name, "string", description, required)
}

fun numberParam(name: String, description: String, required: Boolean = true): ToolSimple.Parameter {
    return ToolSimple.Parameter(name, "number", description, required)
}
