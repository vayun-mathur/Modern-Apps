package com.vayunmathur.games.solitaire.platform

import android.app.Application
import android.content.Context
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.domain.SolitaireDeals
import com.vayunmathur.games.solitaire.domain.SolvableDealTables
import com.vayunmathur.games.solitaire.domain.SolvableDeals
import kotlin.random.Random

/**
 * Where new deals come from: a uniformly random seed from the shipped table of
 * solver-proven seeds for the chosen mode and options (assets/solvable_deals.bin,
 * see domain/SolvableDeals), so every new game is winnable. The tables are a
 * 141 KB asset (14 configs x 10,000 seeds) decoded once per process; picking
 * is a single array read, so nothing is solved on the device.
 */
internal object SolitaireDealSource {
    @Volatile
    private var tables: SolvableDealTables? = null

    fun tables(context: Context): SolvableDealTables? = tables ?: synchronized(this) {
        tables ?: load(context)?.also { tables = it }
    }

    private fun load(context: Context): SolvableDealTables? =
        runCatching {
            context.assets.open(SolvableDeals.ASSET_NAME).use { SolvableDealTables.decode(it.readBytes()) }
        }.getOrNull()

    /**
     * A proven seed for [mode] + [config]. Every config the New Game dialog can
     * produce has a table (enforced by SolvableDealTablesTest); the clock seed
     * is only a last resort for a config outside that set.
     */
    fun pickSeed(context: Context, mode: GameMode, config: GameConfig, random: Random = Random.Default): Long =
        tables(context)?.pick(mode, config, random) ?: System.currentTimeMillis()
}

/** Starts a new game of [mode] with a fresh proven deal for [config]. */
internal fun SolitaireViewModel.startNewDeal(mode: GameMode, config: GameConfig) =
    startDeal(mode, config, SolitaireDealSource.pickSeed(getApplication<Application>(), mode, config))

/**
 * Deals [seed] for [mode] + [config] and starts it as a new game: clears
 * history and records a game played under the stats variant (unchanged keys).
 */
internal fun SolitaireViewModel.startDeal(mode: GameMode, config: GameConfig, seed: Long) {
    uiStateInternal.value = dealtState(mode, config, seed)
    statsRepository.recordGamePlayed(mode, SolvableDeals.variant(mode, config))
}

/**
 * Restarts the current game on the same deal: the board returns to its first
 * position, with the timer, moves and undo history reset. It is the same
 * game, so no extra game is counted in the stats. (`restart()` is different:
 * it is the win screen's "New game" and deals a new seed for the same options.)
 */
fun SolitaireViewModel.restartDeal() {
    val ui = uiStateInternal.value
    val mode = ui.gameMode ?: return
    val seed = when (mode) {
        GameMode.KLONDIKE -> ui.klondike?.seed
        GameMode.SPIDER -> ui.spider?.seed
        GameMode.FREECELL -> ui.freeCell?.seed
        GameMode.PYRAMID -> ui.pyramid?.seed
    } ?: return
    clearMotion()
    cardPositions.clear()
    uiStateInternal.value = dealtState(mode, currentConfig(), seed)
}

/** A fresh game state (empty history) holding the deal of [seed]. */
private fun dealtState(mode: GameMode, config: GameConfig, seed: Long): SolitaireUiState = when (mode) {
    GameMode.KLONDIKE -> SolitaireUiState(gameMode = mode, klondike = SolitaireDeals.klondike(seed, config))
    GameMode.SPIDER -> SolitaireUiState(gameMode = mode, spider = SolitaireDeals.spider(seed, config))
    GameMode.FREECELL -> SolitaireUiState(gameMode = mode, freeCell = SolitaireDeals.freeCell(seed))
    GameMode.PYRAMID -> SolitaireUiState(gameMode = mode, pyramid = SolitaireDeals.pyramid(seed, config))
}
