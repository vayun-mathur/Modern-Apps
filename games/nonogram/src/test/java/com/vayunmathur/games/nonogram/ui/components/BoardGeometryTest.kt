package com.vayunmathur.games.nonogram.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardGeometryTest {
    // 5x5 grid in a 100px viewport: 20px cells.
    private fun viewport(rtl: Boolean = false, zoom: BoardZoom = BoardZoom.Identity, side: Float = 100f) =
        GridViewport(side, 5, rtl, zoom)

    @Test fun ltrMapsPixelsToRowMajorCells() {
        assertEquals(0, viewport().cellAt(1f, 1f))
        assertEquals(7, viewport().cellAt(45f, 30f))
        assertEquals(24, viewport().cellAt(99f, 99f))
    }

    @Test fun rtlMirrorsColumnsButNotRows() {
        // Physical left edge is the last column under RTL.
        assertEquals(4, viewport(rtl = true).cellAt(1f, 1f))
        assertEquals(5, viewport(rtl = true).cellAt(99f, 21f))
        assertEquals(12, viewport(rtl = true).cellAt(50f, 50f))
    }

    @Test fun pointsOffTheViewportClampToTheEdge() {
        assertEquals(0, viewport().cellAt(-30f, -5f))
        assertEquals(24, viewport().cellAt(400f, 101f))
        assertEquals(20, viewport(rtl = true).cellAt(400f, 101f))
    }

    @Test fun zoomedHitTestingWorksInGridCoordinates() {
        // 2x, panned to show the bottom-right quarter: the viewport's top-left is cell (2.5, 2.5).
        val z = BoardZoom(scale = 2f, offsetX = -1f, offsetY = -1f)
        assertEquals(2 * 5 + 2, viewport(zoom = z).cellAt(1f, 1f))
        assertEquals(24, viewport(zoom = z).cellAt(99f, 99f))
        // Each on-screen cell is now 40px, so 45px in is one cell further along.
        assertEquals(3 * 5 + 3, viewport(zoom = z).cellAt(45f, 45f))
        // RTL: the same physical point is the mirrored column.
        assertEquals(3 * 5 + 1, viewport(rtl = true, zoom = z).cellAt(45f, 45f))
        // Dragging off a zoomed viewport stays on the edge of what is visible, not hidden cells.
        assertEquals(2 * 5 + 2, viewport(zoom = z).cellAt(-500f, -500f))
    }

    @Test fun zoomSurvivesASizeChange() {
        // The same zoom on a viewport twice the size shows the same cells at the same relative spots.
        val z = BoardZoom(scale = 3f, offsetX = -1.2f, offsetY = -0.4f)
        for (fx in listOf(0.05f, 0.33f, 0.5f, 0.97f)) for (fy in listOf(0.1f, 0.6f, 0.9f)) {
            assertEquals(
                viewport(zoom = z, side = 100f).cellAt(fx * 100f, fy * 100f),
                viewport(zoom = z, side = 237f).cellAt(fx * 237f, fy * 237f),
            )
        }
    }

    @Test fun cluesFollowTheirLineThroughTheZoom() {
        val z = BoardZoom(scale = 2f, offsetX = -0.5f, offsetY = -0.25f)
        val v = viewport(zoom = z)
        for (i in 0 until 5) {
            // A clue's centre always hits the cell of the line it labels.
            val x = v.columnCenter(i)
            if (x in 0f..100f) assertEquals(i, v.cellAt(x, 50f) % 5)
            val y = v.rowCenter(i)
            if (y in 0f..100f) assertEquals(i, v.cellAt(50f, y) / 5)
        }
        assertEquals(10f, viewport().columnCenter(0)); assertEquals(90f, viewport(rtl = true).columnCenter(0))
        assertEquals(10f * 2 - 50f, v.columnCenter(0))
    }

    @Test fun pinchZoomsAboutTheFingersAndClamps() {
        // Pinching 2x about the centre keeps the centre still.
        val z = BoardZoom.Identity.transformed(PinchFrame(0.5f, 0.5f, factor = 2f), maxScale = 4f)
        assertEquals(2f, z.scale); assertEquals(-0.5f, z.offsetX); assertEquals(-0.5f, z.offsetY)
        // Never past the max, never below 1x, and never panned past an edge.
        assertEquals(4f, z.transformed(PinchFrame(0.5f, 0.5f, factor = 10f), 4f).scale)
        assertEquals(BoardZoom.Identity, z.transformed(PinchFrame(0.5f, 0.5f, factor = 0.1f), 4f))
        val panned = z.transformed(PinchFrame(0.9f, 0.9f, panX = 0.9f, panY = 0.9f), 4f)
        assertEquals(0f, panned.offsetX); assertEquals(0f, panned.offsetY)
        // A zoom saved on a grid that allowed more is pulled back inside the new limit.
        val big = BoardZoom(4f, -3f, -3f).clamped(2f)
        assertEquals(2f, big.scale); assertTrue(big.offsetX >= -1f && big.offsetY >= -1f)
    }
}
