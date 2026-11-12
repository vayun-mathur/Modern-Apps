package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Offline generator for `assets/solvable_deals.bin` (see AGENTS.md, "Solvable
 * deals"). Scans seeds 1, 2, 3, ... per group of configs that share a deal
 * (Klondike draw-1, Klondike draw-3, Pyramid, FreeCell, each Spider suit count)
 * and keeps, per config, the first [target] seeds that [DealProofs] proves.
 *
 * Deterministic regardless of [threads]: seeds are evaluated in fixed rounds,
 * a config stops being evaluated only between rounds, and each table is the
 * ascending prefix of proven seeds, every seed below its last one decided.
 */
class SeedTableGeneration(
    private val target: Int,
    private val threads: Int,
    private val log: (String) -> Unit,
) {

    /** Per-config outcome counts over the seeds scanned for it. */
    class ConfigReport(val key: String) {
        var scanned = 0
        var solved = 0
        var unsolvable = 0
        var unknown = 0
        var lastSeed = 0
        var solveMillis = 0L

        fun line(): String =
            "%-28s table=%5d scanned=%7d solved=%6.2f%% proven-unsolvable=%6.2f%% unknown=%6.2f%% cpu=%ds".format(
                key, solved, scanned, pct(solved), pct(unsolvable), pct(unknown), solveMillis / 1000,
            )

        private fun pct(n: Int) = if (scanned == 0) 0.0 else 100.0 * n / scanned
    }

    private class Group(val name: String, val mode: GameMode, val configs: List<GameConfig>)

    /** Outcome of one seed for one group: per config, solved / unsolvable / unknown / not evaluated. */
    private class SeedOutcome(val seed: Int, val status: Array<SolveStatus?>, val millis: Long)

    fun generate(groups: List<String> = GROUP_NAMES): Pair<Map<String, IntArray>, List<ConfigReport>> {
        val tables = LinkedHashMap<String, IntArray>()
        val reports = ArrayList<ConfigReport>()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            for (group in allGroups().filter { it.name in groups }) {
                val (groupTables, groupReports) = generateGroup(group, pool)
                tables.putAll(groupTables)
                reports.addAll(groupReports)
            }
        } finally {
            pool.shutdownNow()
        }
        return tables to reports
    }

    private fun generateGroup(
        group: Group,
        pool: java.util.concurrent.ExecutorService,
    ): Pair<Map<String, IntArray>, List<ConfigReport>> {
        val keys = group.configs.map { SolvableDeals.tableKey(group.mode, it) }
        val found = keys.map { ArrayList<Int>() }
        val reports = keys.map { ConfigReport(it) }
        var nextSeed = 1
        val started = System.currentTimeMillis()
        while (found.any { it.size < target }) {
            val needed = BooleanArray(keys.size) { found[it].size < target }
            val round = (nextSeed until nextSeed + ROUND_SIZE).map { seed ->
                Callable { evaluate(group, seed, needed) }
            }
            nextSeed += ROUND_SIZE
            for (outcome in pool.invokeAll(round).map { it.get() }) {
                record(outcome, needed, found, reports)
            }
            log(
                "${group.name}: seeds<${nextSeed} " + keys.indices.joinToString(" ") { "${keys[it]}=${found[it].size}" } +
                    " elapsed=${(System.currentTimeMillis() - started) / 1000}s",
            )
        }
        val tables = keys.indices.associate { keys[it] to found[it].take(target).toIntArray() }
        return tables to reports
    }

    private fun record(
        outcome: SeedOutcome,
        needed: BooleanArray,
        found: List<MutableList<Int>>,
        reports: List<ConfigReport>,
    ) {
        for (c in needed.indices) {
            if (!needed[c] || found[c].size >= target) continue
            val report = reports[c]
            report.scanned++
            report.lastSeed = outcome.seed
            report.solveMillis += outcome.millis
            when (outcome.status[c]) {
                SolveStatus.SOLVED -> {
                    report.solved++
                    found[c].add(outcome.seed)
                }
                SolveStatus.UNSOLVABLE -> report.unsolvable++
                else -> report.unknown++
            }
        }
    }

    /**
     * Climbs the group's ladder (configs are ordered strictest first, each a
     * rung of the next): the first rung that solves the seed solves it for
     * every later config. A rung is skipped once no config at or above it
     * still needs seeds; that changes no table, because those tables are full.
     */
    private fun evaluate(group: Group, seed: Int, needed: BooleanArray): SeedOutcome {
        val status = arrayOfNulls<SolveStatus>(group.configs.size)
        val t = System.currentTimeMillis()
        for (rung in group.configs.indices) {
            if ((rung until group.configs.size).none { needed[it] }) break
            val result = DealProofs.solve(group.mode, group.configs[rung], seed.toLong())
            status[rung] = result.status
            if (result.status == SolveStatus.SOLVED) {
                for (c in rung until group.configs.size) status[c] = SolveStatus.SOLVED
                break
            }
        }
        return SeedOutcome(seed, status, System.currentTimeMillis() - t)
    }

    private fun allGroups(): List<Group> {
        val klondike = SolvableDeals.allConfigs.filter { it.first == GameMode.KLONDIKE }.map { it.second }
        fun klondikeGroup(name: String, draw: com.vayunmathur.games.solitaire.data.DrawMode): Group {
            val top = klondike.first { it.drawMode == draw && it.klondikeDifficulty == RELAXED }
            return Group(name, GameMode.KLONDIKE, DealProofs.ladder(GameMode.KLONDIKE, top))
        }
        return listOf(
            klondikeGroup("klondike-draw1", com.vayunmathur.games.solitaire.data.DrawMode.DRAW_ONE),
            klondikeGroup("klondike-draw3", com.vayunmathur.games.solitaire.data.DrawMode.DRAW_THREE),
            Group("pyramid", GameMode.PYRAMID, DealProofs.ladder(GameMode.PYRAMID, GameConfig(relaxed = true))),
            Group("freecell", GameMode.FREECELL, listOf(GameConfig())),
            Group("spider1", GameMode.SPIDER, listOf(GameConfig(spiderSuits = 1))),
            Group("spider2", GameMode.SPIDER, listOf(GameConfig(spiderSuits = 2))),
            Group("spider4", GameMode.SPIDER, listOf(GameConfig(spiderSuits = 4))),
        )
    }

    companion object {
        val GROUP_NAMES = listOf("klondike-draw1", "klondike-draw3", "pyramid", "freecell", "spider1", "spider2", "spider4")
        private const val ROUND_SIZE = 2048
        private val RELAXED = com.vayunmathur.games.solitaire.data.KlondikeDifficulty.RELAXED
    }
}
