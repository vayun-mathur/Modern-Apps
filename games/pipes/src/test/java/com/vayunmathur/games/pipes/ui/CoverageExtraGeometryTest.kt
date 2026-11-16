package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.RenderPos
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageExtraGeometryTest {
    private fun p(r: Int, c: Int) = CellPos(r, c)
    private fun rect(rows: Int, cols: Int) = buildSet {
        for (r in 0 until rows) for (c in 0 until cols) add(p(r, c))
    }

    private fun level(
        cells: Set<CellPos>,
        rows: Int = 4,
        cols: Int = 4,
        renderPositions: Map<CellPos, RenderPos>? = null,
        grid: String = "square",
    ) = LevelData(
        "g", rows, cols, cells, computeAdjacency(cells), renderPositions,
        listOf(EndpointPair(0, listOf(p(0, 0), p(0, 1)))), emptySet(), 1, emptyMap(), grid,
    )

    @Test fun emptyBoardGeometry() {
        val geo = BoardGeometry(BoardLayout(level(emptySet())), 20f)
        assertTrue(geo.cellRects.isEmpty())
        assertEquals(Rect.Zero, geo.boardBounds)
        assertNull(geo.hitTest(Offset(5f, 5f)))
        assertNull(geo.hitTestDrag(Offset(500f, 500f)))
        assertEquals(emptyList(), geo.cellsAlong(Offset(0f, 0f), Offset(50f, 50f)))
        // Zero cell size falls back to a single drag hit-test of the destination.
        val zero = BoardGeometry(BoardLayout(level(rect(2, 2), 2, 2)), 0f)
        assertEquals(listOf(p(0, 0)), zero.cellsAlong(Offset(0f, 0f), Offset(5f, 5f)))
        val negative = BoardGeometry(BoardLayout(level(rect(2, 2), 2, 2)), -3f)
        assertEquals(listOf(p(1, 1)), negative.cellsAlong(Offset(0f, 0f), Offset(-50f, -50f)))
    }

    @Test fun renderPositionLayoutsCentreAndFilter() {
        // Positions for cells off the board are ignored when sizing the board.
        val cells = rect(2, 2)
        val positions = mapOf(p(0, 0) to RenderPos(2f, 3f), p(0, 1) to RenderPos(3f, 3f), p(9, 9) to RenderPos(99f, 99f))
        val layout = BoardLayout(level(cells, 2, 2, renderPositions = positions))
        // Content is 2 wide starting at x=2: the origin shifts it back towards the centre.
        assertTrue(layout.origin(p(0, 0)).x < 2f)
        assertEquals(layout.origin(p(0, 1)).x - layout.origin(p(0, 0)).x, 1f, 0.001f)
        // Empty render-position map: content defaults to a single cell and origins fall back to grid.
        val emptyPos = BoardLayout(level(cells, 2, 2, renderPositions = emptyMap()))
        assertEquals(Offset(1f, 0f), emptyPos.origin(p(0, 1)))
        // A single rendered cell on a larger frame is centred by the shift.
        val single = BoardLayout(level(cells, 5, 5, renderPositions = mapOf(p(0, 0) to RenderPos(0f, 0f))))
        assertTrue(single.origin(p(0, 0)).x > 0f)
        assertEquals(single.origin(p(0, 0)).x, single.origin(p(0, 0)).y, 0.001f)
    }

    @Test fun offsetBoardsAndDragHitTesting() {
        // Cells starting at row/col 2 inside a 6x6 frame are centred, not pinned top-left.
        val cells = buildSet { for (r in 2..3) for (c in 3..4) add(p(r, c)) }
        val layout = BoardLayout(level(cells, 6, 6))
        assertEquals(6f, layout.maxDim)
        assertEquals(Offset(2f, 2f), layout.origin(p(2, 3)))
        val geo = BoardGeometry(layout, 10f)
        assertEquals(p(2, 3), geo.hitTest(Offset(25f, 25f)))
        // Inside the board bounds but in an empty gap: drag hit-test gives up.
        val holed = rect(3, 3) - p(1, 1)
        val holedGeo = BoardGeometry(BoardLayout(level(holed, 3, 3)), 10f)
        assertNull(holedGeo.hitTestDrag(Offset(15f, 15f)))
        // Well outside: the nearest border cell wins, corners included.
        assertEquals(p(0, 0), holedGeo.hitTestDrag(Offset(-100f, -100f)))
        assertEquals(p(2, 2), holedGeo.hitTestDrag(Offset(100f, 100f)))
        // Bounds reflect the drawn cells only.
        assertEquals(0f, holedGeo.boardBounds.left)
        assertEquals(30f, holedGeo.boardBounds.right)
    }

    @Test fun hexHitTestFallsBackToContainingRect() {
        val cells = rect(3, 3)
        val hexLevel = LevelData(
            "h", 3, 3, cells,
            com.vayunmathur.games.pipes.data.computeHexAdjacency(cells),
            com.vayunmathur.games.pipes.data.hexRenderPositions(cells),
            listOf(EndpointPair(0, listOf(p(0, 0), p(2, 2)))), emptySet(), 1, emptyMap(), "hex",
        )
        val geo = BoardGeometry(BoardLayout(hexLevel), 40f)
        assertTrue(geo.isHex)
        // A point inside a cell rect but far from every centre still resolves to that cell.
        val rect00 = geo.cellRects.getValue(p(0, 0))
        assertEquals(p(0, 0), geo.hitTest(Offset(rect00.left + 1f, rect00.top + 1f)))
        assertNull(geo.hitTest(Offset(-500f, -500f)))
    }

    @Test fun cellsAlongReturnsAndZeroDistance() {
        val geo = BoardGeometry(BoardLayout(level(rect(3, 3), 3, 3)), 10f)
        assertEquals(emptyList(), geo.cellsAlong(Offset(5f, 5f), Offset(6f, 6f)))
        // Out and back: the start cell is reported again when the path returns to it.
        val outAndBack = geo.cellsAlong(Offset(5f, 5f), Offset(25f, 5f)) +
            geo.cellsAlong(Offset(25f, 5f), Offset(5f, 5f))
        assertEquals(listOf(p(0, 1), p(0, 2), p(0, 1), p(0, 0)), outAndBack)
    }

    @Test fun rendererPureHelpers() {
        assertEquals(HexDirection.E, hexDirectionBetween(0f, 0f, 10f, 0f))
        assertEquals(HexDirection.SE, hexDirectionBetween(0f, 0f, 5f, 9f))
        assertEquals(HexDirection.SW, hexDirectionBetween(0f, 0f, -5f, 9f))
        assertEquals(HexDirection.W, hexDirectionBetween(0f, 0f, -10f, 0f))
        assertEquals(HexDirection.NW, hexDirectionBetween(0f, 0f, -5f, -9f))
        assertEquals(HexDirection.NE, hexDirectionBetween(0f, 0f, 5f, -9f))
        val rect = Rect(Offset(0f, 0f), Size(100f, 100f))
        assertEquals(Offset(50f, 50f), pipeLabelAnchor(rect, emptySet()))
        assertEquals(Offset(50f, 50f), pipeLabelAnchor(rect, setOf(Direction.UP, Direction.DOWN)))
        assertEquals(Offset(50f, 50f), pipeLabelAnchor(rect, setOf(Direction.LEFT, Direction.RIGHT, Direction.UP)))
        // Corners anchor inside the arc, one inset from each relevant edge.
        val upRight = pipeLabelAnchor(rect, setOf(Direction.UP, Direction.RIGHT))
        assertTrue(upRight.x > 50f && upRight.y < 50f)
        val downLeft = pipeLabelAnchor(rect, setOf(Direction.DOWN, Direction.LEFT))
        assertTrue(downLeft.x < 50f && downLeft.y > 50f)
        assertEquals("A", colorLetter(0))
        assertEquals("Z", colorLetter(25))
        assertEquals("A", colorLetter(26))
        assertEquals("B", colorLetter(27))
        assertTrue(PIPE_COLORS.size >= 17)
        assertEquals(PIPE_COLORS.size, PIPE_COLORS.distinct().size)
    }
}
