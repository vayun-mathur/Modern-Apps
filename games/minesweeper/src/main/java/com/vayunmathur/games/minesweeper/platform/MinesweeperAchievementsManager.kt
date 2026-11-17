package com.vayunmathur.games.minesweeper.platform

import android.content.Context
import com.vayunmathur.games.minesweeper.data.BoardSize
import com.vayunmathur.games.minesweeper.data.Difficulty
import com.vayunmathur.games.minesweeper.data.MinesweeperStatsRepository
import com.vayunmathur.library.util.AchievementsManager

/** Reports Minesweeper achievements and progress to the shared games hub. */
class MinesweeperAchievementsManager(
    context: Context,
    json: String,
    private val statsRepository: MinesweeperStatsRepository,
) : AchievementsManager(context, json) {

    /**
     * Back-fills from the stats file on launch, so an achievement added in a later release is awarded
     * for work the player has already done rather than only for the next field they clear.
     *
     * Only the counters that survive in [MinesweeperStatsRepository] can be recovered. Anything about
     * how a single game was played — flags placed, whether a chord was used — is gone once the game
     * ends, so those are unlocked from the win handler instead.
     */
    override fun checkExistingAchievements() {
        val totalWins = statsRepository.getTotalGamesWon()
        if (totalWins > 0) onAchievementUnlocked("first_win")
        if (statsRepository.getSizeStats(BoardSize.LARGE).gamesWon > 0) {
            onAchievementUnlocked("win_large")
        }
        if (BoardSize.entries.any { statsRepository.getStats(it, Difficulty.EXPERT).gamesWon > 0 }) {
            onAchievementUnlocked("win_expert")
        }
        val best = BoardSize.entries.minOf { statsRepository.getSizeStats(it).bestTimeSeconds }
        if (best < FAST_WIN_SECONDS) onAchievementUnlocked("speed_win")

        onProgressUpdated("wins_10", totalWins)
        onProgressUpdated("wins_50", totalWins)
        onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }

    private companion object {
        /** Matches the "under a minute" wording of the `speed_win` description. */
        const val FAST_WIN_SECONDS = 60
    }
}
