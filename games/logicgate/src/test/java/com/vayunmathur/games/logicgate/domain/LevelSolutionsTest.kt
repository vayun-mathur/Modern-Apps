package com.vayunmathur.games.logicgate.domain

import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.Gate
import com.vayunmathur.games.logicgate.domain.sim.Scoring
import com.vayunmathur.games.logicgate.domain.sim.Verifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LevelSolutionsTest {

    @Test
    fun thereAreTwentyEightLevelsWithUniqueIds() {
        assertEquals(28, LevelRegistry.levels.size)
        assertEquals(28, LevelRegistry.levels.map { it.id }.toSet().size)
    }

    @Test
    fun everyLevelHasAReferenceSolution() {
        assertEquals(LevelRegistry.levels.map { it.id }.toSet(), ReferenceCircuits.levelIds)
    }

    @Test
    fun everyReferenceSolutionPassesItsOwnVerifier() {
        for (level in LevelRegistry.levels) {
            val result = Verifier.verify(level.io, level.tests, ReferenceCircuits.build(level.id))
            assertTrue(result.total > 0, "${level.id} has no tests")
            assertTrue(
                result.solved,
                "${level.id}: ${result.passed}/${result.total}, first failure ${result.firstFailure}, " +
                    "oscillating ${result.oscillating}",
            )
        }
    }

    @Test
    fun everyParIsTheCostOfTheReferenceSolution() {
        for (level in LevelRegistry.levels) {
            val cost = Scoring.cost(ReferenceCircuits.build(level.id)) { Chips[it]?.nandCost }
            assertEquals(level.par, cost, "par of ${level.id}")
        }
    }

    @Test
    fun referenceSolutionsOnlyUseChipsTheLevelOffers() {
        for (level in LevelRegistry.levels) {
            val allowed = LevelRegistry.allowedChips(level.id).toSet()
            val used = ReferenceCircuits.build(level.id).nodes.filterIsInstance<Gate>().map { it.chipId }.toSet()
            assertTrue(allowed.containsAll(used), "${level.id} uses ${used - allowed}")
        }
    }

    @Test
    fun everyOfferedChipWasEarnedOrGiftedEarlier() {
        val earned = mutableSetOf<String>()
        val gifted = mutableSetOf<String>()
        for (level in LevelRegistry.levels) {
            gifted += level.gifts
            for (chip in LevelRegistry.allowedChips(level.id)) {
                assertNotNull(Chips[chip], "unknown chip $chip")
                assertTrue(chip in Chips.base || chip in earned || chip in gifted, "$chip offered early in ${level.id}")
            }
            earned += level.unlocks
        }
    }

    @Test
    fun onlyTheFirstLevelStartsOpenAndEachSolveOpensTheNext() {
        val ids = LevelRegistry.levels.map { it.id }
        assertEquals(listOf("not"), ids.filter { LevelRegistry.isUnlocked(it, emptySet()) })
        assertTrue(LevelRegistry.isUnlocked("mux", setOf("xnor")))
        assertTrue(LevelRegistry.isUnlocked("half_adder", setOf("bus_mux")))
    }
}
