package com.vayunmathur.launcher.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure tests for [CellMetrics.cellAt]: points map to cells, edges clamp
 * into the grid, and empty metrics map everything to the origin.
 */
class CellMetricsTest {

    private val metrics = CellMetrics(columns = 4, rows = 3, cellWidthPx = 100, cellHeightPx = 50)

    @Test fun originMapsToFirstCell() {
        assertEquals(0 to 0, metrics.cellAt(0f, 0f))
    }

    @Test fun pointMapsToItsCell() {
        assertEquals(1 to 2, metrics.cellAt(150f, 125f))
    }

    @Test fun farCornerMapsToLastCell() {
        assertEquals(3 to 2, metrics.cellAt(399f, 149f))
    }

    @Test fun pastTheEdgeClamps() {
        assertEquals(3 to 2, metrics.cellAt(999f, 999f))
        assertEquals(0 to 0, metrics.cellAt(-5f, -5f))
    }

    @Test fun emptyMetricsMapsToOrigin() {
        assertEquals(0 to 0, CellMetrics.Empty.cellAt(150f, 125f))
    }

    @Test fun emptySentinelHasZeroCells() {
        assertEquals(0, CellMetrics.Empty.cellWidthPx)
        assertEquals(0, CellMetrics.Empty.cellHeightPx)
    }
}
