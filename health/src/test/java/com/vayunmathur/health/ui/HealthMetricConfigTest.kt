package com.vayunmathur.health.ui

import com.vayunmathur.health.data.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The metric catalogue behind every chart and dashboard card.
 *
 * A wrong goal or a flipped line/bar flag renders the wrong chart for a
 * metric; a duplicated record type charts two metrics from one series.
 * Pure JVM: the enum needs no framework.
 */
class HealthMetricConfigTest {

    @Test
    fun `every metric has a distinct record type`() {
        val types = HealthMetricConfig.entries.map { it.recordType }
        assertEquals(types.size, types.distinct().size, "two metrics share a RecordType")
    }

    @Test
    fun `every metric has a unit and a title`() {
        for (config in HealthMetricConfig.entries) {
            assertTrue(config.unit.isNotBlank(), "${config.name} has no unit")
            assertTrue(config.titleRes != 0, "${config.name} has no title")
        }
    }

    @Test
    fun `blood pressure is the only dual series metric`() {
        val dual = HealthMetricConfig.entries.filter { it.isDualSeries }
        assertEquals(listOf(HealthMetricConfig.BLOOD_PRESSURE), dual)
        assertEquals(80.0, HealthMetricConfig.BLOOD_PRESSURE.secondaryGoal)
    }

    @Test
    fun `line metrics are the continuous vitals`() {
        assertTrue(HealthMetricConfig.HEART_RATE.isLineChart)
        assertTrue(HealthMetricConfig.WEIGHT.isLineChart)
        assertFalse(HealthMetricConfig.STEPS.isLineChart)
        assertFalse(HealthMetricConfig.SLEEP.isLineChart)
        assertFalse(HealthMetricConfig.HYDRATION.isLineChart)
    }

    @Test
    fun `well known goals are pinned`() {
        assertEquals(10_000.0, HealthMetricConfig.STEPS.dailyGoal)
        assertEquals(120.0, HealthMetricConfig.BLOOD_PRESSURE.dailyGoal)
        assertEquals(2.5, HealthMetricConfig.HYDRATION.dailyGoal)
        assertEquals("steps", HealthMetricConfig.STEPS.unit)
        assertEquals(RecordType.Steps, HealthMetricConfig.STEPS.recordType)
    }

    @Test
    fun `dashboard data defaults to empty`() {
        val empty = MetricDashboardData()
        assertEquals(0.0, empty.totalValue)
        assertEquals(0.0, empty.dailyAverage)
        assertNull(empty.secondaryAverage)
        assertTrue(empty.chartData.isEmpty())
        assertNull(empty.secondaryChartData)
        assertNull(empty.primaryRange)
        assertTrue(empty.historyItems.isEmpty())
        assertEquals(0, empty.totalBarCount)
    }

    @Test
    fun `history items carry goal state`() {
        val item = HistoryItem(
            label = "Monday",
            value = 12_000.0,
            unit = "steps",
            isGoalMet = true,
            useDecimals = false,
        )
        assertTrue(item.isGoalMet)
        assertNull(item.secondaryValue)
        val dual = item.copy(secondaryValue = 80.0, isGoalMet = false)
        assertEquals(80.0, dual.secondaryValue)
        assertFalse(dual.isGoalMet)
    }
}
