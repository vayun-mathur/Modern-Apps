package com.vayunmathur.games.arrows.ui.components

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutePointTest {
    // 5 cols, cell=10: cell index -> centre ((col+.5)*10,(row+.5)*10)
    private val route = listOf(12, 13, 14) // row2 cols2,3,4 => (25,25),(35,25),(45,25)

    @Test
    fun atOrBeforeStartReturnsFirstCentre() {
        assertEquals(Offset(25f, 25f), routePoint(route, 0f, 5, 10f))
        assertEquals(Offset(25f, 25f), routePoint(route, -1f, 5, 10f))
    }

    @Test
    fun interpolatesBetweenCells() {
        assertEquals(Offset(30f, 25f), routePoint(route, 0.5f, 5, 10f))
        assertEquals(Offset(35f, 25f), routePoint(route, 1f, 5, 10f))
        assertEquals(Offset(40f, 25f), routePoint(route, 1.5f, 5, 10f))
    }

    @Test
    fun beyondEndExtrapolatesLastStep() {
        assertEquals(Offset(45f, 25f), routePoint(route, 2f, 5, 10f))
        assertEquals(Offset(55f, 25f), routePoint(route, 3f, 5, 10f))
        assertEquals(Offset(50f, 25f), routePoint(route, 2.5f, 5, 10f))
    }

    @Test
    fun singleCellRouteStaysAtCentre() {
        val single = listOf(12)
        assertEquals(Offset(25f, 25f), routePoint(single, 0f, 5, 10f))
        // Beyond the single cell, previous == end so no movement
        assertEquals(Offset(25f, 25f), routePoint(single, 2f, 5, 10f))
    }

    @Test
    fun verticalRouteInterpolatesDownward() {
        val vertical = listOf(2, 7, 12) // col2 rows0,1,2 => (25,5),(25,15),(25,25)
        assertEquals(Offset(25f, 10f), routePoint(vertical, 0.5f, 5, 10f))
        val beyond = routePoint(vertical, 3f, 5, 10f)
        assertTrue(beyond.y > 25f)
        assertEquals(25f, beyond.x)
    }
}
