package com.vayunmathur.games.minesweeper.data

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
 * Played/won/streak/best-time records, kept per field size and mine density.
 *
 * Records are keyed by variant rather than rolled into one number because a best time only means
 * something next to the field it was set on. The home screen wants a single figure per size, so
 * [getSizeStats] aggregates by key rather than keeping a second denormalised record that could drift.
 *
 * Reads are synchronous and happen during composition. That is fine for SharedPreferences, which is
 * an in-memory map after the first load, and is the reason this is not a DataStore.
 */
class MinesweeperStatsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("minesweeper_stats", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(size: BoardSize, difficulty: Difficulty) = "stats_${size.name}__${difficulty.name}"

    // The prefs file is backup-restored, so a corrupt or cross-version
    // value must read as "no record" instead of crash-looping at startup.
    private fun readKey(key: String): GameStats? =
        prefs.getString(key, null)?.let {
            runCatching { json.decodeFromString<GameStats>(it) }.getOrNull()
        }

    fun getStats(size: BoardSize, difficulty: Difficulty): GameStats =
        readKey(key(size, difficulty)) ?: GameStats()

    /** Every density's record for [size], summed for played/won and best-of for the rest. */
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

    /**
     * Counted on the first reveal rather than when the field is dealt, so backing straight out of a
     * board you never touched does not show up as a played game.
     */
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
}
