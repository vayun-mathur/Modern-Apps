package com.vayunmathur.library.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One record's worth of game history. [bestTimeSeconds] starts at
 * [Int.MAX_VALUE] so "no time yet" and "finished instantly" stay
 * distinguishable and `min` works without a null check.
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
 * Played/won/streak/best-time records over SharedPreferences, shared by
 * the games that track them.
 *
 * sudoku, minesweeper and solitaire each carried a ~100-line copy of
 * this store (same `@Serializable` record, same JSON-in-prefs encoding,
 * same corrupt-value tolerance, same played/won/lost bookkeeping),
 * differing only in the prefs file name and in how their enum types map
 * to record keys. The file names, key strings and JSON shape are
 * unchanged, so existing installs and backup restores read identically;
 * each game keeps a thin repository that owns only its key mapping and
 * exposes `GameStats` under its old package via a typealias.
 *
 * Reads are synchronous. That is fine for SharedPreferences, which is
 * an in-memory map after the first load, and is the reason this is not
 * a DataStore.
 */
class GameStatsStore(context: Context, prefsName: String) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The record at [key], or null when absent or unreadable. The prefs
     * file is backup-restored, so a corrupt or cross-version value must
     * read as "no record" instead of crash-looping at startup.
     */
    fun readOrNull(key: String): GameStats? =
        prefs.getString(key, null)?.let {
            runCatching { json.decodeFromString<GameStats>(it) }.getOrNull()
        }

    /** The record at [key], or a fresh record when there is none. */
    fun read(key: String): GameStats = readOrNull(key) ?: GameStats()

    /** Every key currently stored (solitaire aggregates over these). */
    fun keys(): Set<String> = prefs.all.keys

    private fun save(key: String, stats: GameStats) {
        prefs.edit { putString(key, json.encodeToString(stats)) }
    }

    /** Count a dealt/played game at [key]. */
    fun recordPlayed(key: String) {
        val stats = read(key)
        save(key, stats.copy(gamesPlayed = stats.gamesPlayed + 1))
    }

    /** Count a win at [key] in [timeSeconds], updating streaks and best time. */
    fun recordWon(key: String, timeSeconds: Int) {
        val stats = read(key)
        val streak = stats.currentWinStreak + 1
        save(
            key,
            stats.copy(
                gamesWon = stats.gamesWon + 1,
                currentWinStreak = streak,
                bestWinStreak = maxOf(stats.bestWinStreak, streak),
                bestTimeSeconds = minOf(stats.bestTimeSeconds, timeSeconds),
            ),
        )
    }

    /** Count a loss at [key], resetting the current streak. */
    fun recordLost(key: String) {
        val stats = read(key)
        save(key, stats.copy(currentWinStreak = 0))
    }

    companion object {
        /**
         * Sum played/won over [records] and take the best of the streak
         * and time fields — the per-variant aggregate each game's home
         * screen shows.
         */
        fun aggregate(records: List<GameStats>): GameStats {
            if (records.isEmpty()) return GameStats()
            return GameStats(
                gamesPlayed = records.sumOf { it.gamesPlayed },
                gamesWon = records.sumOf { it.gamesWon },
                currentWinStreak = records.maxOf { it.currentWinStreak },
                bestWinStreak = records.maxOf { it.bestWinStreak },
                bestTimeSeconds = records.minOf { it.bestTimeSeconds },
            )
        }
    }
}
