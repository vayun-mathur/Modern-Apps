package com.vayunmathur.shop.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Nullable JSON navigation helpers. This repo's kotlinx.serialization predates the
 * `jsonObjectOrNull` / `jsonArrayOrNull` / `jsonPrimitiveOrNull` extensions, so
 * dynamic UCP payloads (vendor extensions vary per platform) navigate via `as?`
 * casts here instead of throwing accessors.
 */
internal fun JsonObject.field(key: String): JsonElement? = this[key]

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }

internal fun JsonObject.flag(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }
        ?.let { it == "true" }

internal fun JsonObject.money(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let {
        runCatching { it.content.toLong() }.getOrNull()
            ?: runCatching { it.content.toDouble().toLong() }.getOrNull()
    }

/** UCP truthiness: JSON true or the string "true" (some stores encode flags as strings). */
internal fun JsonElement?.isTruthy(): Boolean? {
    val text = (this as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }
        ?: return null
    return text == "true"
}
