package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Regenerates `src/main/assets/solvable_deals.bin`. With
 * `-PsolitaireSeedGen=<output dir>` (see the module build file and
 * AGENTS.md, "Solvable deals") it writes the full tables plus a
 * `solvable_deals_report.txt` with per-config solvable rates.
 *
 * Without that property it is a deterministic unit test instead of a
 * skip: it generates a tiny FreeCell table (the fastest solver, ~99.9%
 * solvable) into a temp dir and pins the generator's contract — the
 * encoded file decodes, every table is sorted, and every seed it kept
 * really is solver-proven for its config. That keeps the generation
 * path exercised in CI without the 25-minute full run.
 */
class SolvableSeedTableGenerator {

    @Test
    fun generate() {
        val out = System.getProperty("solitaire.seedgen.out")
        if (out.isNullOrBlank()) {
            generateTinyTable()
            return
        }
        val dir = File(out).apply { mkdirs() }
        val target = System.getProperty("solitaire.seedgen.target")?.toIntOrNull() ?: DEFAULT_TARGET
        val threads = System.getProperty("solitaire.seedgen.threads")?.toIntOrNull()?.takeIf { it > 0 }
            ?: Runtime.getRuntime().availableProcessors()
        val groups = System.getProperty("solitaire.seedgen.groups")?.takeIf { it.isNotBlank() }?.split(",")
            ?: SeedTableGeneration.GROUP_NAMES
        val reportFile = File(dir, "solvable_deals_report.txt")
        val log: (String) -> Unit = { line ->
            println(line)
            reportFile.appendText(line + "\n")
        }
        log("target=$target threads=$threads groups=$groups")
        val (tables, reports) = SeedTableGeneration(target, threads, log).generate(groups)
        reports.forEach { log(it.line()) }
        val bytes = SolvableDealTables.encode(tables)
        File(dir, SolvableDeals.ASSET_NAME).writeBytes(bytes)
        log("wrote ${SolvableDeals.ASSET_NAME}: ${bytes.size} bytes, ${tables.size} tables")
    }

    /** The hermetic default: a tiny, deterministic FreeCell generation. */
    private fun generateTinyTable() {
        val dir = Files.createTempDirectory("soldeal-tiny").toFile()
        try {
            val target = 3
            val (tables, reports) = SeedTableGeneration(target, 4) { println(it) }
                .generate(listOf("freecell"))
            val key = SolvableDeals.tableKey(GameMode.FREECELL, GameConfig())
            val seeds = tables[key]
            assertTrue(seeds != null, "no FreeCell table generated: ${reports.map { it.line() }}")
            assertEquals(target, seeds.size, "tiny table should hold exactly the target seeds")
            assertTrue(seeds.all { it > 0 } && seeds.toList() == seeds.sorted(), "table not sorted: ${seeds.toList()}")
            assertEquals(1, reports.size)
            assertEquals(target, reports[0].solved)
            assertTrue(reports[0].scanned >= target, "generator scanned too few seeds: ${reports[0].line()}")

            // The written asset decodes to the same table, and every kept
            // seed is independently re-proven by the solver.
            val file = File(dir, SolvableDeals.ASSET_NAME)
            file.writeBytes(SolvableDealTables.encode(tables))
            val decoded = SolvableDealTables.decode(file.readBytes())
            assertEquals(seeds.toList(), decoded.seeds(GameMode.FREECELL, GameConfig())?.toList())
            for (seed in seeds) {
                val proof = DealProofs.solve(GameMode.FREECELL, GameConfig(), seed.toLong())
                assertEquals(
                    SolveStatus.SOLVED, proof.status,
                    "generated seed $seed does not re-prove: ${proof.status}",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val DEFAULT_TARGET = 10_000
    }
}
