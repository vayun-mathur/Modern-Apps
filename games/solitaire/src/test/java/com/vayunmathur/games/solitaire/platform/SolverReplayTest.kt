package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.domain.DealProofs
import com.vayunmathur.games.solitaire.domain.SolvableDeals
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * For every mode × option, the solver's winning lines on real deals replay
 * through the ViewModel's rules to a won game (see [SolutionReplay]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolverReplayTest {

    @Test
    fun solverLinesWinUnderTheGameRulesForEveryConfig() {
        for ((mode, config) in SolvableDeals.allConfigs) {
            val proven = (1L..MAX_SCAN).asSequence()
                .filter { DealProofs.prove(mode, config, it) != null }
                .take(SAMPLES)
                .toList()
            assertEquals(SAMPLES, proven.size, "$mode $config: fewer than $SAMPLES proven seeds in 1..$MAX_SCAN")
            proven.forEach { SolutionReplay.proveAndReplay(mode, config, it) }
        }
    }

    private companion object {
        const val SAMPLES = 2
        const val MAX_SCAN = 400L
    }
}
