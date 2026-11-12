package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.domain.SolvableDealTables
import com.vayunmathur.games.solitaire.domain.SolvableDeals
import kotlin.random.Random
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * For every mode × option, a sample of shipped table seeds is re-proven and
 * the solver's winning line replays through the ViewModel's rules to a won
 * game (see [SolutionReplay]). A failure here means the rules or a solver
 * drifted from the tables: regenerate them (AGENTS.md, "Solvable deals").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolverReplayTest {

    private val tables: SolvableDealTables by lazy {
        ApplicationProvider.getApplicationContext<Application>().assets.open(SolvableDeals.ASSET_NAME)
            .use { SolvableDealTables.decode(it.readBytes()) }
    }

    @Test
    fun tableSeedsWinUnderTheGameRulesForEveryConfig() {
        // Fixed sample so failures reproduce; Spider solves are the slow ones (seconds each).
        val random = Random(SAMPLE_SEED)
        for ((mode, config) in SolvableDeals.allConfigs) {
            val seeds = tables.seeds(mode, config)!!
            val samples = if (mode == GameMode.SPIDER) SPIDER_SAMPLES else SAMPLES
            repeat(samples) {
                SolutionReplay.proveAndReplay(mode, config, seeds[random.nextInt(seeds.size)].toLong())
            }
        }
    }

    private companion object {
        const val SAMPLES = 4
        const val SPIDER_SAMPLES = 2
        const val SAMPLE_SEED = 914L
    }
}
