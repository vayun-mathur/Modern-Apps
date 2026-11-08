package com.vayunmathur.openassistant.util

import com.vayunmathur.library.log.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

object JsonSchemaValidator {
    fun validateJsonAgainstSchema(jsonString: String, schemaString: String): String? {
        val json = try {
            Json.parseToJsonElement(jsonString)
        } catch (expected: Exception) {
            return "Invalid JSON format: ${expected.message}"
        }
        val schema = try {
            Json.parseToJsonElement(schemaString)
        } catch (expected: Exception) {
            Log.error("JsonSchemaValidator", "Internal Error: Schema itself is invalid JSON", expected)
            return null
        }
        return performValidation(json, schema)
    }

    fun trimJsonKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.map { (k, v) -> k.trim() to trimJsonKeys(v) }.toMap())
        is JsonArray -> JsonArray(element.map { trimJsonKeys(it) })
        else -> element
    }

    private fun performValidation(
        data: JsonElement,
        schema: JsonElement,
        path: String = "",
    ): String? {
        if (schema !is JsonObject) return null
        validateCombinators(data, schema, path)?.let { return it }
        validateType(data, schema, path)?.let { return it }
        if (data is JsonObject) {
            validateObject(data, schema, path)?.let { return it }
        }
        if (data is JsonArray) {
            validateArray(data, schema, path)?.let { return it }
        }
        return null
    }

    private fun validateCombinators(data: JsonElement, schema: JsonObject, path: String): String? {
        validateAnyOf(data, schema, path)?.let { return it }
        validateOneOf(data, schema, path)?.let { return it }
        validateNot(data, schema, path)?.let { return it }
        return null
    }

    private fun validateAnyOf(data: JsonElement, schema: JsonObject, path: String): String? {
        val anyOf = schema["anyOf"] as? JsonArray ?: return null
        val errors = mutableListOf<String>()
        anyOf.forEachIndexed { i, s ->
            val error = performValidation(data, s, path) ?: return null
            errors.add("Option $i: $error")
        }
        return "Data does not match any of the allowed schemas in anyOf. " +
            "Details: ${errors.joinToString("; ")}"
    }

    private fun validateOneOf(data: JsonElement, schema: JsonObject, path: String): String? {
        val oneOf = schema["oneOf"] as? JsonArray ?: return null
        val matching = mutableListOf<Int>()
        val errors = mutableListOf<String>()
        oneOf.forEachIndexed { i, s ->
            val error = performValidation(data, s, path)
            if (error == null) matching.add(i) else errors.add("Option $i: $error")
        }
        if (matching.size == 1) return null
        return if (matching.isEmpty()) {
            "Data does not match any of the allowed options in 'oneOf'. " +
                "Details: ${errors.joinToString("; ")}"
        } else {
            "Data matches MULTIPLE options in 'oneOf': $matching."
        }
    }

    private fun validateNot(data: JsonElement, schema: JsonObject, path: String): String? {
        val notSchema = schema["not"] ?: return null
        if (performValidation(data, notSchema, path) == null) {
            return "Data matched the 'not' schema at $path, which is forbidden."
        }
        return null
    }

    private fun validateType(data: JsonElement, schema: JsonObject, path: String): String? {
        val pathPrefix = if (path.isEmpty()) "" else "at $path: "
        val expectedType = schema["type"]?.jsonPrimitive?.content ?: return null
        if (expectedType == "object" && data !is JsonObject) {
            return "${pathPrefix}Expected an object but got ${data::class.simpleName}"
        }
        if (expectedType == "array" && data !is JsonArray) {
            return "${pathPrefix}Expected an array but got ${data::class.simpleName}"
        }
        return null
    }

    private fun validateObject(data: JsonObject, schema: JsonObject, path: String): String? {
        checkUnknownFields(data, schema, path)?.let { return it }
        checkRequiredFields(data, schema, path)?.let { return it }
        return validateProperties(data, schema, path)
    }

    private fun checkUnknownFields(data: JsonObject, schema: JsonObject, path: String): String? {
        val properties = schema["properties"] as? JsonObject
        data.keys.forEach { key ->
            if (properties == null || !properties.containsKey(key)) {
                val fullPath = if (path.isEmpty()) key else "$path.$key"
                return "Unexpected field found: '$fullPath'."
            }
        }
        return null
    }

    private fun checkRequiredFields(data: JsonObject, schema: JsonObject, path: String): String? {
        (schema["required"] as? JsonArray)?.forEach { req ->
            val fieldName = req.jsonPrimitive.content
            if (!data.containsKey(fieldName)) {
                val fullPath = if (path.isEmpty()) fieldName else "$path.$fieldName"
                return "Missing required field: '$fullPath'"
            }
        }
        return null
    }

    private fun validateProperties(data: JsonObject, schema: JsonObject, path: String): String? {
        val properties = schema["properties"] as? JsonObject
        data.forEach { (key, value) ->
            val propSchema = properties?.get(key) ?: return@forEach
            val fullPath = if (path.isEmpty()) key else "$path.$key"
            if (propSchema is JsonObject) {
                validateConst(value, propSchema, fullPath)?.let { return it }
                validateEnum(value, propSchema, fullPath)?.let { return it }
            }
            performValidation(value, propSchema, fullPath)?.let { return it }
        }
        return null
    }

    private fun validateConst(value: JsonElement, propSchema: JsonObject, fullPath: String): String? {
        val constElement = propSchema["const"] ?: return null
        // Values are model output and the schema arrives with the starting Intent: either side
        // may be a non-primitive, which must be a validation error, never a throw (callers treat
        // a throw as a failed inference instead of continuing to stream).
        val constContent = (constElement as? JsonPrimitive)?.content ?: constElement.toString()
        val valueContent = (value as? JsonPrimitive)?.content ?: value.toString()
        if (valueContent != constContent) {
            return "Field '$fullPath' must be '$constContent' but got '$valueContent'"
        }
        return null
    }

    private fun validateEnum(value: JsonElement, propSchema: JsonObject, fullPath: String): String? {
        val enumValues = propSchema["enum"] as? JsonArray ?: return null
        val allowed = enumValues.map { (it as? JsonPrimitive)?.content ?: it.toString() }
        val valueContent = (value as? JsonPrimitive)?.content ?: value.toString()
        if (valueContent !in allowed) {
            return "Field '$fullPath' has invalid value '$valueContent'. " +
                "Allowed values: $allowed"
        }
        return null
    }

    private fun validateArray(data: JsonArray, schema: JsonObject, path: String): String? {
        val itemSchema = schema["items"] ?: return null
        data.forEachIndexed { index, element ->
            performValidation(element, itemSchema, "$path[$index]")?.let { return it }
        }
        return null
    }
}

/** Extracts the largest balanced `{...}` JSON object from LLM streaming output. */
internal object JsonExtractor {
    fun largestObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(start, i + 1)
                        if (isValidJson(candidate)) return candidate
                    }
                }
            }
        }
        return null
    }

    private fun isValidJson(candidate: String): Boolean = try {
        Json.parseToJsonElement(candidate)
        true
    } catch (expected: IllegalArgumentException) {
        Log.error("JsonExtractor", "Invalid JSON candidate", expected)
        false
    }
}
