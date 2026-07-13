package com.vayunmathur.games.solitaire.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class GameStats(
    val gamesPlayed: Int = 0,
    val gamesWon: Int = 0,
    val currentWinStreak: Int = 0,
    val bestWinStreak: Int = 0,
    val bestTimeSeconds: Int = Int.MAX_VALUE
)

/**
 * Stores stats per (mode, variant) so difficulty variants are tracked
 * separately, while [getModeStats] aggregates all variants of a mode for the
 * grouped per-mode display. Keys look like `stats_KLONDIKE__DRAW_ONE_RELAXED`;
 * a legacy `stats_KLONDIKE` key (no variant) is still folded into the totals.
 */
class SolitaireStatsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("solitaire_stats", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(mode: GameMode, variant: String) = "stats_${mode.name}__$variant"
    private fun modePrefix(mode: GameMode) = "stats_${mode.name}"

    private fun readKey(key: String): GameStats? =
        prefs.getString(key, null)?.let { json.decodeFromString<GameStats>(it) }

    /** Stats for one specific variant of a mode. */
    fun getStats(mode: GameMode, variant: String): GameStats =
        readKey(key(mode, variant)) ?: GameStats()

    /** All stat records belonging to [mode] (every variant + any legacy record). */
    private fun modeRecords(mode: GameMode): List<GameStats> {
        val prefix = modePrefix(mode)
        return prefs.all.keys
            .filter { it == prefix || it.startsWith("${prefix}__") }
            .mapNotNull { readKey(it) }
    }

    /** Aggregated stats for a mode, for the grouped per-mode card. */
    fun getModeStats(mode: GameMode): GameStats {
        val records = modeRecords(mode)
        if (records.isEmpty()) return GameStats()
        return GameStats(
            gamesPlayed = records.sumOf { it.gamesPlayed },
            gamesWon = records.sumOf { it.gamesWon },
            currentWinStreak = records.maxOf { it.currentWinStreak },
            bestWinStreak = records.maxOf { it.bestWinStreak },
            bestTimeSeconds = records.minOf { it.bestTimeSeconds }
        )
    }

    private fun saveStats(mode: GameMode, variant: String, stats: GameStats) {
        prefs.edit().putString(key(mode, variant), json.encodeToString(stats)).apply()
    }

    fun recordGamePlayed(mode: GameMode, variant: String) {
        val stats = getStats(mode, variant)
        saveStats(mode, variant, stats.copy(gamesPlayed = stats.gamesPlayed + 1))
    }

    fun recordGameWon(mode: GameMode, variant: String, timeSeconds: Int, moves: Int) {
        val stats = getStats(mode, variant)
        val newStreak = stats.currentWinStreak + 1
        saveStats(
            mode, variant, stats.copy(
                gamesWon = stats.gamesWon + 1,
                currentWinStreak = newStreak,
                bestWinStreak = maxOf(stats.bestWinStreak, newStreak),
                bestTimeSeconds = minOf(stats.bestTimeSeconds, timeSeconds)
            )
        )
    }

    fun recordGameLost(mode: GameMode, variant: String) {
        val stats = getStats(mode, variant)
        saveStats(mode, variant, stats.copy(currentWinStreak = 0))
    }

    private fun allRecords(): List<GameStats> =
        prefs.all.keys.filter { it.startsWith("stats_") }.mapNotNull { readKey(it) }

    fun getTotalGamesWon(): Int = allRecords().sumOf { it.gamesWon }

    fun getBestWinStreak(): Int = allRecords().maxOfOrNull { it.bestWinStreak } ?: 0
}
