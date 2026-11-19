package com.vayunmathur.games.minesweeper.data

import android.content.Context
import com.vayunmathur.library.util.GameStatsStore

/** Minesweeper's stats record; the shared store's record under its old name. */
typealias GameStats = com.vayunmathur.library.util.GameStats

/**
 * Played/won/streak/best-time records, kept per field size and mine density.
 *
 * Records are keyed by variant rather than rolled into one number because a best time only means
 * something next to the field it was set on. The home screen wants a single figure per size, so
 * [getSizeStats] aggregates by key rather than keeping a second denormalised record that could drift.
 *
 * Storage lives in the shared [GameStatsStore] (`minesweeper_stats` file, same keys and JSON as
 * before); this class owns only the size/difficulty key mapping.
 */
class MinesweeperStatsRepository(context: Context) {
    private val store = GameStatsStore(context, "minesweeper_stats")

    private fun key(size: BoardSize, difficulty: Difficulty) = "stats_${size.name}__${difficulty.name}"

    fun getStats(size: BoardSize, difficulty: Difficulty): GameStats =
        store.read(key(size, difficulty))

    /** Every density's record for [size], summed for played/won and best-of for the rest. */
    fun getSizeStats(size: BoardSize): GameStats =
        GameStatsStore.aggregate(Difficulty.entries.mapNotNull { store.readOrNull(key(size, it)) })

    /**
     * Counted on the first reveal rather than when the field is dealt, so backing straight out of a
     * board you never touched does not show up as a played game.
     */
    fun recordGamePlayed(size: BoardSize, difficulty: Difficulty) =
        store.recordPlayed(key(size, difficulty))

    fun recordGameWon(size: BoardSize, difficulty: Difficulty, timeSeconds: Int) =
        store.recordWon(key(size, difficulty), timeSeconds)

    fun recordGameLost(size: BoardSize, difficulty: Difficulty) =
        store.recordLost(key(size, difficulty))

    fun getTotalGamesWon(): Int =
        BoardSize.entries.sumOf { size -> Difficulty.entries.sumOf { getStats(size, it).gamesWon } }

    fun getBestWinStreak(): Int =
        BoardSize.entries.maxOfOrNull { getSizeStats(it).bestWinStreak } ?: 0
}
