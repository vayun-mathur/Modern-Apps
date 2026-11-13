package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrokeSamplingTest {
    private val cells = buildSet { for (r in 0 until 5) for (c in 0 until 5) add(CellPos(r, c)) }
    private val geo = BoardGeometry(
        BoardLayout(
            LevelData(
                "s", 5, 5, cells, computeAdjacency(cells), null,
                listOf(EndpointPair(0, listOf(CellPos(0, 0), CellPos(4, 4)))), emptySet(), 1,
            )
        ),
        100f,
    )

    @Test fun fastHorizontalSwipeVisitsEveryCell() {
        // One touch event spanning four cells.
        val cells = geo.cellsAlong(Offset(50f, 50f), Offset(450f, 50f))
        assertEquals((1..4).map { CellPos(0, it) }, cells)
    }

    @Test fun diagonalSwipeOnlyEverMovesToNeighbouringCells() {
        val cells = geo.cellsAlong(Offset(50f, 50f), Offset(450f, 430f))
        assertEquals(CellPos(4, 4), cells.last())
        var prev = CellPos(0, 0)
        for (c in cells) {
            val d = kotlin.math.abs(c.row - prev.row) + kotlin.math.abs(c.col - prev.col)
            // Sampling may hop corner-to-corner (StrokeRules routes those), never further.
            assertTrue(d in 1..2 && kotlin.math.abs(c.row - prev.row) <= 1 && kotlin.math.abs(c.col - prev.col) <= 1, "$prev -> $c")
            prev = c
        }
    }

    @Test fun backAndForthIsKeptInOrder() {
        assertEquals(listOf(CellPos(0, 1), CellPos(0, 0)), geo.cellsAlong(Offset(50f, 50f), Offset(150f, 50f)) + geo.cellsAlong(Offset(150f, 50f), Offset(50f, 50f)))
    }

    @Test fun stayingInsideACellYieldsNothing() {
        assertEquals(emptyList(), geo.cellsAlong(Offset(10f, 10f), Offset(90f, 90f)))
    }
}
