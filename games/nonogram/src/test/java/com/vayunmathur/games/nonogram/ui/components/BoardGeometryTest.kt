package com.vayunmathur.games.nonogram.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardGeometryTest {
    // 5x5 grid, 100px square: 20px cells.
    @Test fun ltrMapsPixelsToRowMajorCells() {
        assertEquals(0, BoardGeometry.cellAt(1f, 1f, 100f, 5, rtl = false))
        assertEquals(7, BoardGeometry.cellAt(45f, 30f, 100f, 5, rtl = false))
        assertEquals(24, BoardGeometry.cellAt(99f, 99f, 100f, 5, rtl = false))
    }

    @Test fun rtlMirrorsColumnsButNotRows() {
        // Physical left edge is the last column under RTL.
        assertEquals(4, BoardGeometry.cellAt(1f, 1f, 100f, 5, rtl = true))
        assertEquals(5, BoardGeometry.cellAt(99f, 21f, 100f, 5, rtl = true))
        assertEquals(12, BoardGeometry.cellAt(50f, 50f, 100f, 5, rtl = true))
    }

    @Test fun pointsOffTheGridClampToTheEdge() {
        assertEquals(0, BoardGeometry.cellAt(-30f, -5f, 100f, 5, rtl = false))
        assertEquals(24, BoardGeometry.cellAt(400f, 101f, 100f, 5, rtl = false))
        assertEquals(20, BoardGeometry.cellAt(400f, 101f, 100f, 5, rtl = true))
    }
}
