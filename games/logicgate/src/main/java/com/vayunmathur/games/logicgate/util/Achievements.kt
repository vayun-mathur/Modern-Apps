package com.vayunmathur.games.logicgate.util

import android.content.Context
import com.vayunmathur.games.logicgate.data.LogicProgressRepository
import com.vayunmathur.library.util.AchievementsManager

class LogicAchievementsManager(context: Context, json: String, private val repo: LogicProgressRepository) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        val completed = repo.totalCompleted()
        if (completed > 0) onAchievementUnlocked("first_gate")
        onProgressUpdated("foundation_complete", if (completed >= 3) completed else 0)
        onProgressUpdated("routing_complete", if (completed >= 7) completed else 0)
        onProgressUpdated("arith_complete", if (completed >= 11) completed else 0)
        onProgressUpdated("memory_complete", if (completed >= 14) completed else 0)
        onProgressUpdated("cpu_complete", completed)
        onProgressUpdated("optimal_5", repo.getLevelStats().count { it.value.bestScore <= (com.vayunmathur.games.logicgate.data.Levels.byId[it.key]?.optimalNands ?: 999) })
        onProgressUpdated("all_levels", completed)
    }
}
