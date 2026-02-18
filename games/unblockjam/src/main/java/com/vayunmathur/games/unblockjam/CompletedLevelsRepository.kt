package com.vayunmathur.games.unblockjam

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LevelStats(val bestScore: Int)

class CompletedLevelsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("level_stats", Context.MODE_PRIVATE)
    private val levelStatsKey = "level_stats_map"

    fun getLevelStats(): Map<String, LevelStats> {
        val jsonString = prefs.getString(levelStatsKey, "{}") ?: "{}"
        return Json.decodeFromString<Map<String, LevelStats>>(jsonString)
    }

    fun updateBestScore(levelIndex: Int, score: Int) {
        val allStats = getLevelStats().toMutableMap()
        val currentStats = allStats[levelIndex.toString()]
        if (currentStats == null || score < currentStats.bestScore) {
            allStats[levelIndex.toString()] = LevelStats(bestScore = score)
            val jsonString = Json.encodeToString(allStats)
            prefs.edit {
                putString(levelStatsKey, jsonString)
            }
        }
    }
}