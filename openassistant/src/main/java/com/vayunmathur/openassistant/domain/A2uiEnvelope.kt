package com.vayunmathur.openassistant.domain

import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage
import androidx.a2ui.model.protocol.A2uiServerToClientMessage
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One surface per assistant message, so replaying a conversation recreates each in place. */
fun a2uiSurfaceId(messageId: Long): String = "m$messageId"

/**
 * The protocol messages for a block the model wrote, or null when it is not usable.
 *
 * The model writes only `{"components": [...], "data": {...}}` to keep its reply short; the
 * envelope (`createSurface`, surface and catalog ids) is supplied here. A bare component array
 * is accepted too, since that is the commonest way a small model drops the wrapper.
 */
fun buildA2uiEnvelope(surfaceId: String, catalogId: String, json: String): List<A2uiServerToClientMessage>? {
    val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
    val (componentsJson, dataJson) = when (root) {
        is JsonArray -> root to null
        is JsonObject -> (root["components"] as? JsonArray ?: return null) to root["data"] as? JsonObject
        else -> return null
    }
    val components = componentsJson.mapNotNull { toComponent(it) }
    if (components.isEmpty()) return null
    return buildList {
        add(A2uiCreateSurfaceMessage(surfaceId, catalogId))
        add(A2uiUpdateComponentsMessage(surfaceId, components))
        if (dataJson != null) add(A2uiUpdateDataModelMessage(surfaceId, "/", jsonToValue(dataJson)))
    }
}

/** The user turn sent back to the model when a surface fires an event. */
fun actionTurnText(eventName: String, context: Map<String, Any?>): String =
    if (context.isEmpty()) {
        "[UI action \"$eventName\"]"
    } else {
        "[UI action \"$eventName\": ${valueToJson(context)}]"
    }

private fun toComponent(element: JsonElement): A2uiComponentPayload? {
    val obj = element as? JsonObject ?: return null
    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val type = (obj["component"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val properties = obj.filterKeys { it != "id" && it != "component" }.mapValues { jsonToValue(it.value) }
    return A2uiComponentPayload(id, type, properties)
}

/** JSON as the plain types A2UI's data model and payloads use: Map, List, String, Number, Boolean, null. */
fun jsonToValue(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonObject -> element.mapValues { jsonToValue(it.value) }
    is JsonArray -> element.map { jsonToValue(it) }
    is JsonPrimitive -> when {
        element.isString -> element.content
        else -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull
    }
}

fun valueToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to valueToJson(v) })
    is Iterable<*> -> JsonArray(value.map { valueToJson(it) })
    else -> JsonPrimitive(value.toString())
}
