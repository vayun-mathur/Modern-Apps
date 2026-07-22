package com.vayunmathur.games.logicgate.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class LogicProgressRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("logicgate_stats", Context.MODE_PRIVATE)

    fun getCompletedLevelIds(): Set<String> {
        return prefs.getStringSet(KEY_COMPLETED, emptySet())?.toSet() ?: emptySet()
    }

    fun isLevelComplete(id: String): Boolean = id in getCompletedLevelIds()

    fun markCompleted(id: String) {
        val current = getCompletedLevelIds()
        if (id !in current) {
            prefs.edit { putStringSet(KEY_COMPLETED, (current + id).toMutableSet()) }
        }
    }

    fun unlockedChipIds(): Set<String> {
        val completed = getCompletedLevelIds()
        val chips = mutableSetOf("NAND")
        for (lvlId in completed) {
            Levels.byId[lvlId]?.unlocksChipId?.let { chips.add(it) }
        }
        return chips
    }

    fun totalCompleted(): Int = getCompletedLevelIds().size

    fun incCircuitsChecked() { prefs.edit { putInt("circuits_checked", prefs.getInt("circuits_checked", 0) + 1) } }
    fun getCircuitsChecked(): Int = prefs.getInt("circuits_checked", 0)

    // For achievements compatibility
    fun getLevelStats(): Map<String, DummyStat> = getCompletedLevelIds().associateWith { DummyStat() }

    data class DummyStat(val bestScore: Int = 0)

    companion object {
        private const val KEY_COMPLETED = "completed_levels"
    }
}
