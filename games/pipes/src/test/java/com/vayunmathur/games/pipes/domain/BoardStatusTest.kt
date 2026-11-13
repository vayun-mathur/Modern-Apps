package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardStatusTest {
    private fun p(r: Int, c: Int) = CellPos(r, c)
    private val cells = buildSet { for (r in 0 until 2) for (c in 0 until 3) add(p(r, c)) }

    /** 2x3: red (0,0)-(0,2) on top, blue (1,0)-(1,2) below. */
    private val level = LevelData(
        "b", 2, 3, cells, computeAdjacency(cells), null,
        listOf(EndpointPair(0, listOf(p(0, 0), p(0, 2))), EndpointPair(1, listOf(p(1, 0), p(1, 2)))),
        emptySet(), 2,
    )

    private fun board(vararg paths: Pair<Int, List<CellPos>>) =
        BoardState(paths.toMap(), BoardEdits.owners(level, paths.toMap()))

    @Test fun fullAndConnectedIsSolved() {
        val b = board(0 to listOf(p(0, 0), p(0, 1), p(0, 2)), 1 to listOf(p(1, 0), p(1, 1), p(1, 2)))
        assertTrue(BoardStatus.isSolved(level, b))
        assertFalse(BoardStatus.needsFill(level, b))
    }

    @Test fun connectedWithEmptyCellsNeedsFill() {
        // Same pairs plus a seventh cell (2,1) that neither line covers.
        val holeLevel = level.copy(
            cells = cells + p(2, 1),
            adjacency = computeAdjacency(cells + p(2, 1)),
        )
        val paths = mapOf(0 to listOf(p(0, 0), p(0, 1), p(0, 2)), 1 to listOf(p(1, 0), p(1, 1), p(1, 2)))
        val b = BoardState(paths, BoardEdits.owners(holeLevel, paths))
        assertTrue(BoardStatus.needsFill(holeLevel, b))
        assertFalse(BoardStatus.isSolved(holeLevel, b))
    }

    @Test fun anUnconnectedPairNeedsNoFillHint() {
        val b = board(0 to listOf(p(0, 0), p(0, 1), p(0, 2)), 1 to listOf(p(1, 0), p(1, 1)))
        assertFalse(BoardStatus.needsFill(level, b))
        assertFalse(BoardStatus.isSolved(level, b))
        assertFalse(BoardStatus.needsFill(level, BoardState()))
    }
}
