package com.vayunmathur.openassistant.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class Tool(
    val type: String,
    val function: FunctionSpec
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject
)

data class ToolResult(val llmResponse: String, val userResponse: String)

data class ToolSimple(
    val name: String,
    val description: String,
    val params: List<Parameter>,
    val action: ToolFunctionType,
) {
    data class Parameter(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean,
    )

    fun toTool(): Tool {
        return Tool(
            type = "function",
            function = FunctionSpec(
                name = name,
                description = description,
                parameters = buildJsonObject {
                    putJsonObject("properties") {
                        params.forEach {
                            putJsonObject(it.name) {
                                put("type", it.type)
                                put("description", it.description)
                            }
                        }
                    }
                    putJsonArray("required") {
                        params.filter { it.required }.forEach {
                            add(it.name)
                        }
                    }
                }
            )
        )
    }
}

fun stringParam(name: String, description: String, required: Boolean = true): ToolSimple.Parameter {
    return ToolSimple.Parameter(name, "string", description, required)
}

fun numberParam(name: String, description: String, required: Boolean = true): ToolSimple.Parameter {
    return ToolSimple.Parameter(name, "number", description, required)
}
