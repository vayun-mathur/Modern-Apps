package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty

/**
 * How a seed is proven winnable for a config — the single definition both the
 * offline table generator and the tests use.
 *
 * Pass limits nest: a line that wins with one pass through the stock also wins
 * with two, three or unlimited passes (unused recycles change nothing), and a
 * single-pass Pyramid win also wins relaxed. So a config's [ladder] lists the
 * configs from the strictest up to itself, and a seed belongs to the config's
 * table when the solver wins it at any rung. Trying strict rungs first is
 * also faster: their searches are smaller.
 */
object DealProofs {

    /** Klondike pass limits from 1 pass to unlimited. */
    private val PASS_LADDER = listOf(
        KlondikeDifficulty.HARD,
        KlondikeDifficulty.TWO_PASSES,
        KlondikeDifficulty.REGULAR,
        KlondikeDifficulty.RELAXED,
    )

    fun ladder(mode: GameMode, config: GameConfig): List<GameConfig> = when (mode) {
        GameMode.KLONDIKE -> PASS_LADDER
            .take(PASS_LADDER.indexOf(config.klondikeDifficulty) + 1)
            .map { config.copy(klondikeDifficulty = it) }
        GameMode.PYRAMID -> if (config.relaxed) listOf(config.copy(relaxed = false), config) else listOf(config)
        else -> listOf(config)
    }

    /** One solver run on the fresh deal of [seed] under exactly [config], at the default budget. */
    fun solve(mode: GameMode, config: GameConfig, seed: Long): SolveResult = when (mode) {
        GameMode.KLONDIKE -> KlondikeSolver.solve(SolitaireDeals.klondike(seed, config))
        GameMode.SPIDER -> SpiderSolver.solve(SolitaireDeals.spider(seed, config))
        GameMode.FREECELL -> FreeCellSolver.solve(SolitaireDeals.freeCell(seed))
        GameMode.PYRAMID -> PyramidSolver.solve(SolitaireDeals.pyramid(seed, config))
    }

    /**
     * The first ladder rung that wins [seed], with its winning line (valid as-is
     * under [config]), or null when no rung proves it.
     */
    fun prove(mode: GameMode, config: GameConfig, seed: Long): Pair<GameConfig, SolveResult>? {
        for (rung in ladder(mode, config)) {
            val result = solve(mode, rung, seed)
            if (result.status == SolveStatus.SOLVED) return rung to result
        }
        return null
    }
}
