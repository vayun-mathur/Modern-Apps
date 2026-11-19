package com.vayunmathur.games.sudoku.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One record's worth of history. [bestTimeSeconds] starts at [Int.MAX_VALUE] so "no time yet" and
 * "finished instantly" stay distinguishable and `min` works without a null check.
 */
@Serializable
data class GameStats(
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val currentWinStreak: Int = 0,
    val bestWinStreak: Int = 0,
    val bestTimeSeconds: Int = Int.MAX_VALUE,
)

/**
 * Played/won/streak/best-time records, kept per board size and difficulty.
 *
 * Records are keyed by variant rather than rolled into one number because a best time only means
 * something next to the shape it was set on — 90 seconds is a slow 4x4 and a remarkable 9x9. The
 * home screen still wants a single figure per size, so [getSizeStats] aggregates by key prefix
 * instead of keeping a second denormalised record that could drift.
 *
 * Reads are synchronous and happen during composition. That is fine for SharedPreferences, which is
 * an in-memory map after the first load, and is the reason this is not a DataStore.
 */
class SudokuStatsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sudoku_stats", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(size: BoardSize, difficulty: Difficulty) =
        "${sizePrefix(size)}__${difficulty.name}"

    private fun sizePrefix(size: BoardSize) = "stats_${size.name}"

    // The prefs file is backup-restored, so a corrupt or cross-version
    // value must read as "no record" instead of crash-looping at startup.
    private fun readKey(key: String): GameStats? =
        prefs.getString(key, null)?.let {
            runCatching { json.decodeFromString<GameStats>(it) }.getOrNull()
        }

    fun getStats(size: BoardSize, difficulty: Difficulty): GameStats =
        readKey(key(size, difficulty)) ?: GameStats()

    /** Every difficulty's record for [size], summed for played/won and best-of for the rest. */
    fun getSizeStats(size: BoardSize): GameStats {
        val records = Difficulty.entries.mapNotNull { readKey(key(size, it)) }
        if (records.isEmpty()) return GameStats()
        return GameStats(
            gamesPlayed = records.sumOf { it.gamesPlayed },
            gamesWon = records.sumOf { it.gamesWon },
            currentWinStreak = records.maxOf { it.currentWinStreak },
            bestWinStreak = records.maxOf { it.bestWinStreak },
            bestTimeSeconds = records.minOf { it.bestTimeSeconds },
        )
    }

    private fun save(size: BoardSize, difficulty: Difficulty, stats: GameStats) {
        prefs.edit { putString(key(size, difficulty), json.encodeToString(stats)) }
    }

    /** Counted when the puzzle is dealt, so an abandoned game still shows up as played. */
    fun recordGamePlayed(size: BoardSize, difficulty: Difficulty) {
        val stats = getStats(size, difficulty)
        save(size, difficulty, stats.copy(gamesPlayed = stats.gamesPlayed + 1))
    }

    fun recordGameWon(size: BoardSize, difficulty: Difficulty, timeSeconds: Int) {
        val stats = getStats(size, difficulty)
        val streak = stats.currentWinStreak + 1
        save(
            size, difficulty,
            stats.copy(
                gamesWon = stats.gamesWon + 1,
                currentWinStreak = streak,
                bestWinStreak = maxOf(stats.bestWinStreak, streak),
                bestTimeSeconds = minOf(stats.bestTimeSeconds, timeSeconds),
            )
        )
    }

    fun recordGameLost(size: BoardSize, difficulty: Difficulty) {
        val stats = getStats(size, difficulty)
        save(size, difficulty, stats.copy(currentWinStreak = 0))
    }

    fun getTotalGamesWon(): Int =
        BoardSize.entries.sumOf { size -> Difficulty.entries.sumOf { getStats(size, it).gamesWon } }

    fun getBestWinStreak(): Int =
        BoardSize.entries.maxOfOrNull { getSizeStats(it).bestWinStreak } ?: 0

    /** Best time on [difficulty] across every board size, or null if it has never been won. */
    fun getBestTime(difficulty: Difficulty): Int? =
        BoardSize.entries.minOf { getStats(it, difficulty).bestTimeSeconds }
            .takeIf { it < Int.MAX_VALUE }
}
