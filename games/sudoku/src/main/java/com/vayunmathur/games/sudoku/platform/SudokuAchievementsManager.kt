package com.vayunmathur.games.sudoku.platform

import android.content.Context
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuStatsRepository
import com.vayunmathur.library.util.AchievementsManager

/** Reports Sudoku achievements and progress to the shared games hub. */
class SudokuAchievementsManager(
    context: Context,
    json: String,
    private val statsRepository: SudokuStatsRepository,
) : AchievementsManager(context, json) {

    /**
     * Back-fills from the stats file on launch, so an achievement added in a later release is
     * awarded for work the player has already done rather than only for the next puzzle they finish.
     *
     * Only the counters that survive in [SudokuStatsRepository] can be recovered this way. Anything
     * about how a single game was played (hints, mistakes) is gone once the game ends, so those are
     * unlocked from the win handler instead.
     */
    override fun checkExistingAchievements() {
        val totalWins = statsRepository.getTotalGamesWon()
        if (totalWins > 0) onAchievementUnlocked("first_win")
        if (statsRepository.getSizeStats(BoardSize.NINE).gamesWon > 0) {
            onAchievementUnlocked("win_nine")
        }
        if (BoardSize.entries.any { statsRepository.getStats(it, Difficulty.EXPERT).gamesWon > 0 }) {
            onAchievementUnlocked("win_expert")
        }
        if (BoardSize.entries.all { statsRepository.getSizeStats(it).gamesWon > 0 }) {
            onAchievementUnlocked("every_size")
        }
        if (statsRepository.getSizeStats(BoardSize.NINE).bestTimeSeconds < SPEED_SECONDS) {
            onAchievementUnlocked("speed_nine")
        }

        onProgressUpdated("wins_10", totalWins)
        onProgressUpdated("wins_50", totalWins)
        onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }

    /** Achievement thresholds for [SudokuAchievementsManager]. */
    companion object {
        /** Matches the "under five minutes" wording of the `speed_nine` description. */
        const val SPEED_SECONDS = 300
    }
}
