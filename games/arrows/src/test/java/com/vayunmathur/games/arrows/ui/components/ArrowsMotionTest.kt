package com.vayunmathur.games.arrows.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The snake timing, as pure arithmetic: the head sets off first and the tail follows a beat
 * later, without any segment ever passing the move's destination.
 */
class ArrowsMotionTest {

    @Test
    fun headMovesImmediatelyWhileTheTailStillWaits() {
        // Four segments: at a tenth of a cell of global travel only the head has started.
        assertEquals(0.1f, laggedAdvance(0.1f, segmentIndex = 3, segmentCount = 4, maxAdvance = 5f))
        assertEquals(0f, laggedAdvance(0.1f, segmentIndex = 0, segmentCount = 4, maxAdvance = 5f))
    }

    @Test
    fun segmentsStartInOrderFromHeadToTail() {
        val count = 5
        var global = 0f
        val started = mutableListOf<Int>()
        while (global <= 1f) {
            for (index in 0 until count) {
                if (index !in started && laggedAdvance(global, index, count, maxAdvance = 5f) > 0f) {
                    started += index
                }
            }
            global += 0.05f
        }
        assertEquals(listOf(4, 3, 2, 1, 0), started, "head first, tail last")
    }

    @Test
    fun everySegmentLandsExactlyOnMaxAdvanceAtTheGlobalTarget() {
        val max = 7f
        for (count in 1..6) {
            val target = globalAdvanceTarget(max, count)
            for (index in 0 until count) {
                assertEquals(
                    max, laggedAdvance(target, index, count, max),
                    "segment $index of $count should finish at max",
                )
            }
        }
    }

    @Test
    fun advanceIsOrderedAndBoundedThroughoutTheMove() {
        val count = 4
        val max = 3f
        var global = 0f
        val target = globalAdvanceTarget(max, count)
        while (global <= target) {
            val advances = (0 until count).map { laggedAdvance(global, it, count, max) }
            assertTrue(advances.all { it in 0f..max }, "bounded at global $global: $advances")
            assertTrue(
                advances.zipWithNext().all { (behind, ahead) -> behind <= ahead },
                "tail never ahead of head at global $global: $advances",
            )
            global += 0.1f
        }
    }

    @Test
    fun singleSegmentHasNoLagAndMatchesGlobal() {
        assertEquals(2f, globalAdvanceTarget(2f, segmentCount = 1))
        assertEquals(1.5f, laggedAdvance(1.5f, segmentIndex = 0, segmentCount = 1, maxAdvance = 2f))
    }
}
