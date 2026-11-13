package com.vayunmathur.games.pipes.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unfinished boards, one encoded string per level id, so Back, the level arrows and
 * process death never lose a game in progress. Kept in their own prefs file (not
 * level_stats, whose size feeds the achievements) and capped to the [MAX_LEVELS] most
 * recently saved levels; a won or restarted level drops its entry.
 */
class ProgressRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(levelId: String): String? = prefs.getString(key(levelId), null)

    fun save(levelId: String, encoded: String) {
        val order = (recent() - levelId) + levelId
        prefs.edit {
            putString(key(levelId), encoded)
            for (id in order.dropLast(MAX_LEVELS)) remove(key(id))
            putString(ORDER_KEY, encodeOrder(order.takeLast(MAX_LEVELS)))
        }
    }

    fun clear(levelId: String) {
        if (!prefs.contains(key(levelId))) return
        prefs.edit {
            remove(key(levelId))
            putString(ORDER_KEY, encodeOrder(recent() - levelId))
        }
    }

    private fun recent(): List<String> = runCatching {
        Json.parseToJsonElement(prefs.getString(ORDER_KEY, null) ?: "[]").jsonArray.map { it.jsonPrimitive.content }
    }.getOrDefault(emptyList())

    private fun encodeOrder(ids: List<String>) = JsonArray(ids.map { JsonPrimitive(it) }).toString()

    private fun key(levelId: String) = "level_$levelId"

    companion object {
        const val PREFS_NAME = "pipes_progress"
        private const val ORDER_KEY = "order"
        private const val MAX_LEVELS = 30
    }
}
