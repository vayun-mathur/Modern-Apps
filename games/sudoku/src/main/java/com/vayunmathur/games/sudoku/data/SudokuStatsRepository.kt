package com.vayunmathur.games.sudoku.data

import android.content.Context
import com.vayunmathur.library.util.GameStatsStore

/** Sudoku's stats record; the shared store's record under its old name. */
typealias GameStats = com.vayunmathur.library.util.GameStats

/**
 * Played/won/streak/best-time records, kept per board size and difficulty.
 *
 * Records are keyed by variant rather than rolled into one number because a best time only means
 * something next to the shape it was set on — 90 seconds is a slow 4x4 and a remarkable 9x9. The
 * home screen still wants a single figure per size, so [getSizeStats] aggregates by key prefix
 * instead of keeping a second denormalised record that could drift.
 *
 * Storage lives in the shared [GameStatsStore] (`sudoku_stats` file, same keys and JSON as
 * before); this class owns only the size/difficulty key mapping.
 */
class SudokuStatsRepository(context: Context) {
    private val store = GameStatsStore(context, "sudoku_stats")

    private fun key(size: BoardSize, difficulty: Difficulty) =
        "${sizePrefix(size)}__${difficulty.name}"

    private fun sizePrefix(size: BoardSize) = "stats_${size.name}"

    fun getStats(size: BoardSize, difficulty: Difficulty): GameStats =
        store.read(key(size, difficulty))

    /** Every difficulty's record for [size], summed for played/won and best-of for the rest. */
    fun getSizeStats(size: BoardSize): GameStats =
        GameStatsStore.aggregate(Difficulty.entries.mapNotNull { store.readOrNull(key(size, it)) })

    /** Counted when the puzzle is dealt, so an abandoned game still shows up as played. */
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

    /** Best time on [difficulty] across every board size, or null if it has never been won. */
    fun getBestTime(difficulty: Difficulty): Int? =
        BoardSize.entries.minOf { getStats(it, difficulty).bestTimeSeconds }
            .takeIf { it < Int.MAX_VALUE }
}
