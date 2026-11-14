package com.vayunmathur.games.pipes.platform

import android.content.Context
import com.vayunmathur.games.pipes.data.CompletedLevelsRepository
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Manages pipes achievements. */
class PipesAchievementsManager(
    context: Context,
    json: String,
    private val repository: CompletedLevelsRepository
) : AchievementsManager(context, json) {

    private val dailyStore = DailyChallengeStore(context, "pipes_daily")

    override fun checkExistingAchievements() {
        val stats = repository.getLevelStats()
        if (stats.isNotEmpty()) {
            onAchievementUnlocked("first_flow")
            onAchievementUnlocked("first_level")
        }
        onProgressUpdated("level_50", stats.size)
        onProgressUpdated("pipes_1000", repository.getTotalPipesPlaced())

        CoroutineScope(Dispatchers.IO).launch {
            val bestStreak = dailyStore.bestStreak()
            if (bestStreak > 0) {
                onProgressUpdated("daily_streak_7", bestStreak.toInt())
                onProgressUpdated("daily_streak_30", bestStreak.toInt())
            }
        }
    }
}
