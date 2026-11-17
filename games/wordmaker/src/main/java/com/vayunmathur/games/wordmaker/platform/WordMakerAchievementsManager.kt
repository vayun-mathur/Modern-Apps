package com.vayunmathur.games.wordmaker.platform

import android.content.Context
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Unlocks Word Maker achievements from level progress, bonus words, and daily streaks. */
class WordMakerAchievementsManager(
    context: Context,
    json: String,
    private val levelDataStore: LevelDataStore
) : AchievementsManager(context, json) {

    private val dailyStore = DailyChallengeStore(context, "wordmaker_daily")

    override fun checkExistingAchievements() {
        CoroutineScope(Dispatchers.IO).launch {
            val currentLevel = levelDataStore.currentLevel.first()
            if (currentLevel > 1) onAchievementUnlocked("level_1_done")
            
            onProgressUpdated("manual_levels_done", currentLevel - 1)
            onProgressUpdated("level_50", currentLevel)
            onProgressUpdated("level_100", currentLevel)
            onProgressUpdated("level_500", currentLevel)
            
            val totalBonus = levelDataStore.totalBonusWords.first()
            onProgressUpdated("bonus_hunter", totalBonus)

            val bestStreak = dailyStore.bestStreak()
            if (bestStreak > 0) {
                onAchievementUnlocked("first_daily")
                onProgressUpdated("daily_streak_7", bestStreak.toInt())
                onProgressUpdated("daily_streak_30", bestStreak.toInt())
            }
        }
    }
}
