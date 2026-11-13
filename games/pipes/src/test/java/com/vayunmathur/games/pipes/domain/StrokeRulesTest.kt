package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrokeRulesTest {
    private fun p(r: Int, c: Int) = CellPos(r, c)
    private val cells = buildSet { for (r in 0 until 5) for (c in 0 until 5) add(p(r, c)) }

    /** Red 0: (0,0)-(4,4). Blue 1: (0,4)-(4,0). */
    private val level = LevelData(
        "s", 5, 5, cells, computeAdjacency(cells), null,
        listOf(EndpointPair(0, listOf(p(0, 0), p(4, 4))), EndpointPair(1, listOf(p(0, 4), p(4, 0)))),
        emptySet(), 2,
    )

    private fun ext(path: List<CellPos>, cell: CellPos, owners: Map<CellPos, Int> = emptyMap()) =
        StrokeRules.extend(level, owners, 0, path, cell)

    @Test fun adjacentStepAppends() {
        assertEquals(listOf(p(0, 0), p(0, 1)), ext(listOf(p(0, 0)), p(0, 1)))
    }

    @Test fun diagonalStepRoutesThroughAnOrthogonalNeighbour() {
        val out = ext(listOf(p(0, 0), p(1, 0), p(1, 1)), p(2, 2))
        assertEquals(5, out.size)
        assertEquals(p(2, 2), out.last())
        val via = out[3]
        assertTrue(via == p(1, 2) || via == p(2, 1), "via=$via")
    }

    @Test fun diagonalPrefersTheNeighbourThatCutsNothing() {
        // (2,1) belongs to blue, so the route goes through (1,2) and cuts nothing.
        val out = ext(listOf(p(0, 0), p(1, 0), p(1, 1)), p(2, 2), owners = mapOf(p(2, 1) to 1))
        assertEquals(listOf(p(0, 0), p(1, 0), p(1, 1), p(1, 2), p(2, 2)), out)
    }

    @Test fun skippedCellOnAStraightLineIsFilledIn() {
        assertEquals(listOf(p(0, 0), p(0, 1), p(0, 2)), ext(listOf(p(0, 0)), p(0, 2)))
    }

    @Test fun cellsThreeStepsAwayAreIgnored() {
        assertEquals(listOf(p(0, 0)), ext(listOf(p(0, 0)), p(0, 3)))
    }

    @Test fun reEnteringAnEarlierCellCutsBackToIt() {
        val path = listOf(p(0, 0), p(0, 1), p(0, 2), p(1, 2), p(2, 2))
        assertEquals(listOf(p(0, 0), p(0, 1)), ext(path, p(0, 1)))
        assertEquals(listOf(p(0, 0)), ext(path, p(0, 0)))
        assertEquals(path, ext(path, p(2, 2)))
    }

    @Test fun foreignEndpointsBlockButOtherLinesDoNot() {
        assertEquals(listOf(p(0, 3)), StrokeRules.extend(level, emptyMap(), 0, listOf(p(0, 3)), p(0, 4)))
        // Running into blue's line is allowed: BoardEdits.applyStroke cuts blue there.
        assertEquals(listOf(p(0, 0), p(0, 1)), ext(listOf(p(0, 0)), p(0, 1), owners = mapOf(p(0, 1) to 1)))
        assertEquals(listOf(p(0, 0), p(0, 1), p(0, 2)), ext(listOf(p(0, 0)), p(0, 2), owners = mapOf(p(0, 1) to 1)))
    }

    @Test fun completedStrokeStaysPut() {
        val path = listOf(p(0, 0), p(1, 0), p(2, 0), p(3, 0), p(3, 1), p(4, 1), p(4, 2), p(4, 3), p(4, 4))
        assertTrue(StrokeRules.isComplete(level, 0, path))
        assertEquals(path, ext(path, p(3, 4)))
        assertEquals(path, ext(path, p(4, 2)))
        assertFalse(StrokeRules.isComplete(level, 0, listOf(p(0, 0))))
    }

    @Test fun diagonalNeverRoutesThroughThePartnerDot() {
        // From (4,3) to (3,4) the shared neighbours are (3,3) and the partner dot (4,4);
        // finishing on the dot would strand the target cell, so the route takes (3,3).
        val path = listOf(p(0, 0), p(0, 1), p(1, 1), p(2, 1), p(3, 1), p(4, 1), p(4, 2), p(4, 3))
        assertEquals(path + p(3, 3) + p(3, 4), ext(path, p(3, 4)))
    }
}
