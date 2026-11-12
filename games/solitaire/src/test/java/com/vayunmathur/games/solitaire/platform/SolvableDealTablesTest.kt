package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.domain.SolvableDealTables
import com.vayunmathur.games.solitaire.domain.SolvableDeals
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shipped seed tables and how new games, restarts and replays use them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolvableDealTablesTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val tables: SolvableDealTables by lazy {
        app.assets.open(SolvableDeals.ASSET_NAME).use { SolvableDealTables.decode(it.readBytes()) }
    }

    private fun seedOf(vm: SolitaireViewModel): Long = with(vm.uiState.value) {
        klondike?.seed ?: spider?.seed ?: freeCell?.seed ?: pyramid?.seed ?: error("no game")
    }

    @Test fun everyDialogConfigHasAFullSortedTable() {
        for ((mode, config) in SolvableDeals.allConfigs) {
            val seeds = assertNotNull(tables.seeds(mode, config), "$mode $config has no table")
            assertTrue(seeds.size >= MIN_TABLE, "$mode $config: only ${seeds.size} seeds")
            assertTrue(seeds.first() > 0 && (1 until seeds.size).all { seeds[it] > seeds[it - 1] })
        }
    }

    @Test fun newGamesOnlyDealTableSeedsForEveryConfig() {
        val vm = SolutionReplay.vm()
        for ((mode, config) in SolvableDeals.allConfigs) {
            val table = tables.seeds(mode, config)!!.toSet()
            repeat(PICKS) {
                vm.selectMode(mode, config)
                val seed = seedOf(vm)
                assertTrue(seed.toInt() in table, "$mode $config dealt $seed, not a table seed")
                assertEquals(SolvableDeals.variant(mode, config), currentVariantOf(vm))
            }
            // The win screen's "New game" (restart) also deals from the same table.
            vm.restart()
            assertTrue(seedOf(vm).toInt() in table)
        }
    }

    private fun currentVariantOf(vm: SolitaireViewModel): String = vm.currentVariant()

    @Test fun pickedSeedDealsExactlyTheProvenBoard() {
        // A new game is exactly SolitaireDeals of its seed: the board the solver proved.
        val vm = SolutionReplay.vm()
        val config = GameConfig()
        vm.selectMode(GameMode.KLONDIKE, config)
        val state: KlondikeState = vm.uiState.value.klondike!!
        assertEquals(com.vayunmathur.games.solitaire.domain.SolitaireDeals.klondike(state.seed, config), state)
    }

    @Test fun restartDealKeepsTheSameDeal() {
        val vm = SolutionReplay.vm()
        for ((mode, config) in SolvableDeals.allConfigs) {
            vm.selectMode(mode, config)
            val fresh = vm.uiState.value
            when (mode) {
                GameMode.KLONDIKE -> vm.drawFromStock()
                GameMode.SPIDER -> vm.dealSpiderStock()
                GameMode.FREECELL -> vm.freeCellMoveToFreeCell(0, 0)
                GameMode.PYRAMID -> vm.pyramidDealStock()
            }
            vm.incrementTimer()
            assertNotEquals(fresh, vm.uiState.value, "$mode $config: the setup move did nothing")
            vm.restartDeal()
            assertEquals(fresh, vm.uiState.value, "$mode $config: restartDeal changed the deal")
        }
    }

    @Test fun restartDealDoesNotCountAnotherGame() {
        val vm = SolutionReplay.vm()
        vm.selectMode(GameMode.FREECELL, GameConfig())
        val played = vm.getStats(GameMode.FREECELL).gamesPlayed
        vm.restartDeal()
        assertEquals(played, vm.getStats(GameMode.FREECELL).gamesPlayed)
    }

    @Test fun pickIsUniformOverTheTable() {
        // Not stuck on a few entries: many draws spread over many distinct seeds.
        val config = GameConfig()
        val random = Random(SAMPLE_SEED)
        val seen = (1..2000).map { tables.pick(GameMode.FREECELL, config, random) }.toSet()
        assertTrue(seen.size > 1500, "only ${seen.size} distinct of 2000 picks")
    }

    @Test fun dailyDealIsDeterministicAndProven() {
        for ((mode, config) in SolvableDeals.allConfigs) {
            val table = tables.seeds(mode, config)!!.toSet()
            val day = tables.seedForDay(mode, config, DAY)!!
            assertEquals(day, tables.seedForDay(mode, config, DAY))
            assertTrue(day.toInt() in table)
        }
    }

    private companion object {
        const val MIN_TABLE = 5_000
        const val PICKS = 5
        const val SAMPLE_SEED = 20_261_112L
        const val DAY = 20_769L
    }
}
