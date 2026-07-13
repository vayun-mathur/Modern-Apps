package com.vayunmathur.games.solitaire.util

import android.content.Context
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.SolitaireStatsRepository
import com.vayunmathur.library.util.AchievementsManager

class SolitaireAchievementsManager(
    context: Context,
    json: String,
    private val statsRepository: SolitaireStatsRepository
) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        val totalWins = statsRepository.getTotalGamesWon()
        if (totalWins > 0) onAchievementUnlocked("first_win")
        for (mode in GameMode.entries) {
            val stats = statsRepository.getModeStats(mode)
            if (stats.gamesWon > 0) {
                when (mode) {
                    GameMode.KLONDIKE -> onAchievementUnlocked("klondike_first")
                    GameMode.SPIDER -> onAchievementUnlocked("spider_first")
                    GameMode.FREECELL -> onAchievementUnlocked("freecell_first")
                    GameMode.PYRAMID -> onAchievementUnlocked("pyramid_first")
                }
            }
        }
        onProgressUpdated("wins_10", totalWins)
        onProgressUpdated("wins_50", totalWins)
        onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }
}
