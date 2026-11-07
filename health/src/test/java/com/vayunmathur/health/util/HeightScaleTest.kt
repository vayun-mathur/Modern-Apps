package com.vayunmathur.health.util

import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.library.util.Tuple3
import kotlin.test.Test
import kotlin.test.assertEquals

/** Height is stored in meters but displayed in cm; other metrics pass through. */
class HeightScaleTest {
    @Test
    fun heightScalesMetersToCentimeters() {
        val out = HealthChartData.scaleForDisplay(
            HealthMetricConfig.HEIGHT,
            listOf(Tuple3(20000L, 1.75, 1.75)),
        )
        assertEquals(175.0, out.single().second)
        assertEquals(20000L, out.single().first)
    }

    @Test
    fun weightPassesThroughUnscaled() {
        val input = listOf(Tuple3(20000L, 75.0, 0.0))
        assertEquals(input, HealthChartData.scaleForDisplay(HealthMetricConfig.WEIGHT, input))
    }
}
