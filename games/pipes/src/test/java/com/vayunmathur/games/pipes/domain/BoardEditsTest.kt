package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardEditsTest {
    private fun p(r: Int, c: Int) = CellPos(r, c)
    private val cells = buildSet { for (r in 0 until 5) for (c in 0 until 5) add(p(r, c)) }
    private fun level(bridges: Set<CellPos> = emptySet()) = LevelData(
        "e", 5, 5, cells, computeAdjacency(cells), null,
        listOf(EndpointPair(0, listOf(p(0, 0), p(4, 0))), EndpointPair(1, listOf(p(2, 0), p(2, 4)))),
        bridges, 2,
    )

    /** Blue (1) drawn from (2,4) leftwards along row 2 to (2,1). */
    private val blue = listOf(p(2, 4), p(2, 3), p(2, 2), p(2, 1))

    private fun board(level: LevelData) =
        BoardState(mapOf(1 to blue), BoardEdits.owners(level, mapOf(1 to blue)))

    @Test fun crossingAnotherLineCutsItKeepingTheDrawnFromEnd() {
        val level = level()
        val red = listOf(p(0, 0), p(0, 1), p(0, 2), p(1, 2), p(2, 2), p(3, 2))
        val out = BoardEdits.applyStroke(level, board(level), 0, red)
        assertEquals(red, out.paths[0])
        assertEquals(listOf(p(2, 4), p(2, 3)), out.paths[1])
        assertEquals(0, out.cellOwner[p(2, 2)])
        assertNull(out.cellOwner[p(2, 1)])
        assertEquals(1, out.cellOwner[p(2, 3)])
    }

    @Test fun aRemnantShorterThanTwoCellsIsDropped() {
        val level = level()
        val red = listOf(p(0, 0), p(0, 1), p(0, 2), p(0, 3), p(1, 3), p(2, 3))
        val out = BoardEdits.applyStroke(level, board(level), 0, red)
        assertNull(out.paths[1])
    }

    @Test fun bridgesAreSharedNotCutAndKeepTheirFirstOwner() {
        val level = level(bridges = setOf(p(2, 2)))
        val red = listOf(p(0, 0), p(0, 1), p(0, 2), p(1, 2), p(2, 2), p(3, 2))
        val out = BoardEdits.applyStroke(level, board(level), 0, red)
        assertEquals(blue, out.paths[1])
        assertEquals(1, out.cellOwner[p(2, 2)])
    }

    @Test fun shortStrokeRemovesTheColourAndBaseIsUntouched() {
        val level = level()
        val base = board(level)
        val out = BoardEdits.applyStroke(level, base, 1, listOf(p(2, 4)))
        assertEquals(emptyMap(), out.paths)
        assertEquals(emptyMap(), out.cellOwner)
        assertEquals(blue, base.paths[1])
        // Re-applying the same line is an identity, which is what makes no-op strokes detectable.
        assertEquals(base, BoardEdits.applyStroke(level, base, 1, blue))
    }
}
