package com.vayunmathur.messages.util

import com.vayunmathur.messages.data.Reaction
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Per-message reaction state, stored inside the message's
 * [com.vayunmathur.messages.data.Message.serviceData] JSON so no Room schema
 * migration is needed. WhatsApp (and similar platforms) deliver reactions as
 * per-sender events — a sender can hold at most one reaction, sending again
 * switches it and an empty emoji removes it — so we keep a `senderId -> emoji`
 * map here and DERIVE the aggregated emoji+count list the UI renders into the
 * separate reactions_json column via [reactionsJsonFromServiceData].
 */
private const val REACTIONS_KEY = "reactions"

/** Variation-selector-16; peers' emoji arrive stripped of it, so normalize the
 *  local user's palette emoji the same way for stable aggregation. */
private const val VARIATION_SELECTOR = "\uFE0F"

/**
 * Set or clear one [senderId]'s reaction inside [serviceData] JSON. A null/blank
 * [emoji] removes that sender's reaction. Returns the updated serviceData JSON,
 * preserving any unrelated keys.
 */
internal fun applyReactionToServiceData(serviceData: String?, senderId: String, emoji: String?): String {
    val obj = runCatching {
        if (serviceData.isNullOrBlank()) JSONObject() else JSONObject(serviceData)
    }.getOrDefault(JSONObject())
    val map = obj.optJSONObject(REACTIONS_KEY) ?: JSONObject()
    val normalized = emoji?.replace(VARIATION_SELECTOR, "")
    if (normalized.isNullOrEmpty()) map.remove(senderId) else map.put(senderId, normalized)
    obj.put(REACTIONS_KEY, map)
    return obj.toString()
}

/**
 * Aggregate the per-sender reactions stored in [serviceData] into the emoji+count
 * list the UI renders, serialized as reactions_json. Returns null when there are
 * no reactions.
 */
internal fun reactionsJsonFromServiceData(serviceData: String?): String? {
    if (serviceData.isNullOrBlank()) return null
    val map = runCatching { JSONObject(serviceData).optJSONObject(REACTIONS_KEY) }.getOrNull() ?: return null
    if (map.length() == 0) return null
    val counts = LinkedHashMap<String, Int>()
    for (key in map.keys()) {
        val emoji = map.optString(key).takeIf { it.isNotBlank() } ?: continue
        counts[emoji] = (counts[emoji] ?: 0) + 1
    }
    if (counts.isEmpty()) return null
    val list = counts.map { Reaction(it.key, it.value) }
    return Json.encodeToString(ListSerializer(Reaction.serializer()), list)
}
