package com.vayunmathur.library.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A level's best score, persisted as JSON in shared preferences. */
@Serializable
data class LevelStats(val bestScore: Int)

/** SharedPreferences-backed store of per-level best scores and counters. */
open class LevelStatsRepository(context: Context, prefsName: String = "level_stats") {

    protected val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val levelStatsKey = "level_stats_map"

    fun getLevelStats(): Map<String, LevelStats> {
        val jsonString = prefs.getString(levelStatsKey, "{}") ?: "{}"
        // Persisted JSON can be corrupted (backup restore, partial write); a
        // decode failure must not crash every caller on every launch.
        return runCatching { Json.decodeFromString<Map<String, LevelStats>>(jsonString) }
            .getOrDefault(emptyMap())
    }

    fun updateBestScore(levelId: String, score: Int) {
        val allStats = getLevelStats().toMutableMap()
        val currentStats = allStats[levelId]
        if (currentStats == null || score < currentStats.bestScore) {
            allStats[levelId] = LevelStats(bestScore = score)
            prefs.edit { putString(levelStatsKey, Json.encodeToString(allStats)) }
        }
    }

    /**
     * Drops every level not in [levelIds]. Used by stores that only ever hold one day's levels, so
     * the map does not grow without bound.
     */
    fun retainOnly(levelIds: Set<String>) {
        val allStats = getLevelStats()
        val kept = allStats.filterKeys { it in levelIds }
        if (kept.size == allStats.size) return
        prefs.edit { putString(levelStatsKey, Json.encodeToString(kept)) }
    }

    protected fun incrementCounter(key: String) {
        prefs.edit { putInt(key, prefs.getInt(key, 0) + 1) }
    }

    protected fun getCounter(key: String): Int = prefs.getInt(key, 0)
}
