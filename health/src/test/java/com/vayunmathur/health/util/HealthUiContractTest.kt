package com.vayunmathur.health.util

import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.ui.HealthMetricConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * Tests for the UI contract values between the ViewModels and the
 * preview-renderable screens.
 *
 * These pin the no-op defaults (a preview must render with zero behaviour)
 * and the zero-value defaults the screens design their empty states around.
 */
class HealthUiContractTest {

    @Test
    fun `today state defaults to zero`() {
        val state = TodayUiState()
        assertEquals(0L, state.steps)
        assertEquals(0L, state.activeCalories)
        assertEquals(0L, state.mindfulnessMinutes)
        assertEquals(0.0, state.distanceKm)
        assertEquals(0.0, state.hydrationMl)
        assertEquals(0L, state.heartRateMin)
        assertEquals(0L, state.heartRateMax)
        assertEquals(0.0, state.elevationMeters)
        assertEquals(0L, state.wheelchairPushes)
        assertEquals(0L, state.exerciseMinutes)
        assertEquals(MainPageMetrics(), state.metrics)
    }

    @Test
    fun `today actions default to no-ops`() {
        val noop = TodayActions.Noop
        // Must not throw: a preview renders with these and no behaviour.
        noop.openSleepDetails()
        noop.openExerciseDetails()
        noop.openMetric(HealthMetricConfig.STEPS)
    }

    @Test
    fun `nutrition state defaults to empty`() {
        val state = NutritionUiState()
        assertEquals(NutritionData(), state.totals)
        assertEquals(0, state.mealCount)
        assertEquals(0.0, state.mealCalories)
    }

    @Test
    fun `nutrition actions default to no-ops`() {
        val noop = NutritionActions.Noop
        noop.logHydration()
        noop.logMeal()
        noop.openRecipes()
        noop.openFullBreakdown()
    }

    @Test
    fun `metric details state carries its date`() {
        val today = LocalDate(2026, 9, 16)
        val state = MetricDetailsUiState(config = HealthMetricConfig.SLEEP, today = today)
        assertEquals(HealthMetricConfig.SLEEP, state.config)
        assertEquals(today, state.today)
        assertTrue(state.data.chartData.isEmpty())
    }

    @Test
    fun `metric details actions default to no-ops`() {
        val noop = MetricDetailsActions.Noop
        noop.loadBarChartData(HealthMetricConfig.STEPS, LocalDate(2026, 9, 16), 1)
        noop.navigateUp()
    }

    @Test
    fun `main page metrics default to unknown`() {
        val metrics = MainPageMetrics()
        assertEquals(null, metrics.spo2)
        assertEquals(null, metrics.bloodPressure)
        assertEquals(null, metrics.sleepMinutes)
        assertEquals(null, metrics.weight)
    }
}
