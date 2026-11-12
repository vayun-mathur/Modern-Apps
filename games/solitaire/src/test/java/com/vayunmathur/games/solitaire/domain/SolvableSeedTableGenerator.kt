package com.vayunmathur.games.solitaire.domain

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regenerates `src/main/assets/solvable_deals.bin`. Skipped in normal test runs;
 * runs only when Gradle is given `-PsolitaireSeedGen=<output dir>` (see the
 * module build file and AGENTS.md, "Solvable deals"). Writes the table plus a
 * `solvable_deals_report.txt` with per-config solvable rates.
 */
class SolvableSeedTableGenerator {

    @Test
    fun generate() {
        val out = System.getProperty("solitaire.seedgen.out")
        assumeTrue("offline generator: pass -PsolitaireSeedGen=<dir>", !out.isNullOrBlank())
        val dir = File(out!!).apply { mkdirs() }
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

    private companion object {
        const val DEFAULT_TARGET = 10_000
    }
}
