package com.vayunmathur.games.wordmaker.util

import android.content.Context
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WordMakerAchievementsManager(
    context: Context,
    json: String,
    private val levelDataStore: LevelDataStore
) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        CoroutineScope(Dispatchers.IO).launch {
            val englishLevel = levelDataStore.englishLevel.first()
            val currentLevel = levelDataStore.currentLevel.first()
            if (englishLevel > 1 || currentLevel > 1) onAchievementUnlocked("level_1_done")

            onProgressUpdated("manual_levels_done", englishLevel - 1)
            onProgressUpdated("level_50", englishLevel)
            onProgressUpdated("level_100", englishLevel)
            onProgressUpdated("level_500", englishLevel)

            val totalBonus = levelDataStore.totalBonusWords.first()
            onProgressUpdated("bonus_hunter", totalBonus)
        }
    }
}
