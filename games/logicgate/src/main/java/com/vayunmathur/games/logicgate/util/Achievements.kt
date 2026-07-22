package com.vayunmathur.games.logicgate.util

import android.content.Context
import com.vayunmathur.games.logicgate.data.ChapterId
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.data.LogicProgressRepository
import com.vayunmathur.library.util.AchievementsManager

class LogicAchievementsManager(context: Context, json: String, private val repo: LogicProgressRepository) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        val stats = repo.getLevelStats()
        val completedIds = stats.keys
        if (completedIds.isNotEmpty()) onAchievementUnlocked("first_gate")

        val optimalCount = completedIds.count { id ->
            val best = stats[id]?.bestScore ?: 9999
            val optimal = Levels.byId[id]?.optimalNands ?: 9999
            best <= optimal
        }

        onProgressUpdated("optimal_5", optimalCount)
        onProgressUpdated("all_levels", completedIds.size)

        Levels.chapters.forEach { ch ->
            val count = completedIds.count { Levels.byId[it]?.chapter == ch.id }
            val key = when (ch.id) {
                ChapterId.FOUNDATION -> "foundation_complete"
                ChapterId.ROUTING -> "routing_complete"
                ChapterId.ARITH -> "arith_complete"
                ChapterId.MEMORY -> "memory_complete"
                ChapterId.CPU -> "cpu_complete"
            }
            onProgressUpdated(key, count)
        }
    }
}
