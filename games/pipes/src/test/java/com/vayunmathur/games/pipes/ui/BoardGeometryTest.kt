package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.computeAdjacency
import com.vayunmathur.games.pipes.data.computeHexAdjacency
import com.vayunmathur.games.pipes.data.hexRenderPositions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BoardGeometryTest {
    private fun rect(rows: Int, cols: Int) =
        buildSet { for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c)) }

    private fun level(cells: Set<CellPos>, rows: Int, cols: Int) = LevelData(
        "g", rows, cols, cells, computeAdjacency(cells), null,
        listOf(EndpointPair(0, listOf(cells.first(), cells.last()))), emptySet(), 1,
    )

    @Test fun squareCellsMapToPixelRectsAndBack() {
        val geo = BoardGeometry(BoardLayout(level(rect(5, 5), 5, 5)), 60f)
        assertEquals(5f, geo.layout.maxDim)
        val r = geo.cellRects.getValue(CellPos(2, 3))
        assertEquals(180f, r.left); assertEquals(120f, r.top); assertEquals(60f, r.width)
        assertEquals(CellPos(2, 3), geo.hitTest(Offset(190f, 130f)))
        assertNull(geo.hitTest(Offset(-5f, 10f)))
        // Off the board while dragging: the nearest border cell keeps driving the stroke
        assertEquals(CellPos(0, 4), geo.hitTestDrag(Offset(400f, -40f)))
    }

    @Test fun sameLayoutRescalesWithCellSize() {
        val layout = BoardLayout(level(rect(5, 5), 5, 5))
        val big = BoardGeometry(layout, 60f)
        val small = BoardGeometry(layout, 30f)
        assertSame(big.layout, small.layout)
        val p = Offset(135f, 15f)
        assertEquals(CellPos(0, 2), big.hitTest(p))
        assertEquals(CellPos(0, 4), small.hitTest(p))
    }

    @Test fun irregularShapesAreCentredAndHolesAreNotDragTargets() {
        // A 3-wide column inside a 5x5 frame is centred horizontally.
        val cells = buildSet { for (r in 0 until 5) for (c in 1..3) add(CellPos(r, c)) }
        val geo = BoardGeometry(BoardLayout(level(cells, 5, 5)), 10f)
        assertEquals(10f, geo.cellRects.getValue(CellPos(0, 1)).left)
        val holed = rect(3, 3) - CellPos(1, 1)
        val g2 = BoardGeometry(BoardLayout(level(holed, 3, 3)), 10f)
        assertNull(g2.hitTestDrag(Offset(15f, 15f)))
    }

    @Test fun hexUsesNearestCentre() {
        val cells = rect(3, 3)
        val hex = LevelData(
            "h", 3, 3, cells, computeHexAdjacency(cells), hexRenderPositions(cells),
            listOf(EndpointPair(0, listOf(CellPos(0, 0), CellPos(2, 2)))), emptySet(), 1, emptyMap(), "hex",
        )
        val geo = BoardGeometry(BoardLayout(hex), 40f)
        for ((cell, r) in geo.cellRects) assertEquals(cell, geo.hitTest(r.center))
    }
}
