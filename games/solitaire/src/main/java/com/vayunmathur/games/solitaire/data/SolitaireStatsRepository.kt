package com.vayunmathur.games.solitaire.data

import android.content.Context
import com.vayunmathur.library.util.GameStatsStore

/** Solitaire's stats record; the shared store's record under its old name. */
typealias GameStats = com.vayunmathur.library.util.GameStats

/**
 * Stores stats per (mode, variant) so difficulty variants are tracked
 * separately, while [getModeStats] aggregates all variants of a mode for the
 * grouped per-mode display. Keys look like `stats_KLONDIKE__DRAW_ONE_RELAXED`;
 * a legacy `stats_KLONDIKE` key (no variant) is still folded into the totals.
 *
 * Storage lives in the shared [GameStatsStore] (`solitaire_stats` file, same keys and JSON as
 * before); this class owns only the mode/variant key mapping.
 */
class SolitaireStatsRepository(context: Context) {
    private val store = GameStatsStore(context, "solitaire_stats")

    private fun key(mode: GameMode, variant: String) = "stats_${mode.name}__$variant"
    private fun modePrefix(mode: GameMode) = "stats_${mode.name}"

    /** Stats for one specific variant of a mode. */
    fun getStats(mode: GameMode, variant: String): GameStats =
        store.read(key(mode, variant))

    /** All stat records belonging to [mode] (every variant + any legacy record). */
    private fun modeRecords(mode: GameMode): List<GameStats> {
        val prefix = modePrefix(mode)
        return store.keys()
            .filter { it == prefix || it.startsWith("${prefix}__") }
            .mapNotNull { store.readOrNull(it) }
    }

    /** Aggregated stats for a mode, for the grouped per-mode card. */
    fun getModeStats(mode: GameMode): GameStats =
        GameStatsStore.aggregate(modeRecords(mode))

    fun recordGamePlayed(mode: GameMode, variant: String) =
        store.recordPlayed(key(mode, variant))

    fun recordGameWon(mode: GameMode, variant: String, timeSeconds: Int) =
        store.recordWon(key(mode, variant), timeSeconds)

    fun recordGameLost(mode: GameMode, variant: String) =
        store.recordLost(key(mode, variant))

    private fun allRecords(): List<GameStats> =
        store.keys().filter { it.startsWith("stats_") }.mapNotNull { store.readOrNull(it) }

    fun getTotalGamesWon(): Int = allRecords().sumOf { it.gamesWon }

    fun getBestWinStreak(): Int = allRecords().maxOfOrNull { it.bestWinStreak } ?: 0
}
