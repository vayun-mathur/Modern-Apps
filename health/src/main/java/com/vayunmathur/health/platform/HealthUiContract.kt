package com.vayunmathur.health.platform
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.MetricDashboardData
import kotlinx.datetime.LocalDate

/**
 * The UI contract between [HealthViewModel] plus the back stack and the handful of screens
 * the store listing is captured from.
 *
 * Those screens take a state value and an actions interface rather than the ViewModel, so
 * they can be rendered by a `@Preview` — see `src/screenshotTest`, which is where the
 * listing images come from. Health Connect, Room and the bundled food database do not exist
 * there, which is exactly what makes the images reproducible from a clean checkout.
 *
 * Only Today, Nutrition and the metric detail screen are split this way. Every other screen
 * still takes the ViewModel directly; splitting the whole app would be a large change for
 * no benefit.
 *
 * It lives in `util` rather than `ui` so the dependency runs one way: `ui` depends on
 * `util`, never the reverse.
 */

/** Everything the Today screen draws. Sums are for the current day. */
data class TodayUiState(
    val steps: Long = 0L,
    val activeCalories: Long = 0L,
    val mindfulnessMinutes: Long = 0L,
    val distanceKm: Double = 0.0,
    val floors: Double = 0.0,
    val hydrationMl: Double = 0.0,
    val heartRateMin: Long = 0L,
    val heartRateMax: Long = 0L,
    val elevationMeters: Double = 0.0,
    val wheelchairPushes: Long = 0L,
    val exerciseMinutes: Long = 0L,
    val metrics: MainPageMetrics = MainPageMetrics(),
)

/**
 * Today's callbacks — all navigation. Every method has a no-op default so a preview can
 * render the screen without supplying behaviour; [Noop] is the whole implementation a
 * preview needs.
 */
interface TodayActions {
    fun openSleepDetails() {}
    fun openExerciseDetails() {}
    fun openMetric(config: HealthMetricConfig) {}

    /** Shared constants and helpers for TodayActions. */
    companion object {
        val Noop: TodayActions = object : TodayActions {}
    }
}

/** Everything the Nutrition screen draws: today's summed nutrients plus the meal tally. */
data class NutritionUiState(
    val totals: NutritionData = NutritionData(),
    val mealCount: Int = 0,
    val mealCalories: Double = 0.0,
)

/**
 * Nutrition callbacks. The two logging dialogs need the ViewModel, so they stay hosted by
 * the binder and the screen only asks for them to be opened.
 */
interface NutritionActions {
    fun logHydration() {}
    fun logMeal() {}
    fun openRecipes() {}
    fun openFullBreakdown() {}

    /** Shared constants and helpers for NutritionActions. */
    companion object {
        val Noop: NutritionActions = object : NutritionActions {}
    }
}

/** Everything the per-metric chart screen draws. */
data class MetricDetailsUiState(
    val config: HealthMetricConfig,
    /** Today, passed in rather than read from the clock so a preview renders a fixed date. */
    val today: LocalDate,
    val data: MetricDashboardData = MetricDashboardData(),
)

/** Chart-screen callbacks. Same no-op-default arrangement as [TodayActions]. */
interface MetricDetailsActions {
    /** Same name and signature as [HealthViewModel.loadBarChartData], which does the work. */
    fun loadBarChartData(config: HealthMetricConfig, anchorDate: LocalDate, selectedTab: Int) {}
    fun navigateUp() {}

    /** Shared constants and helpers for MetricDetailsActions. */
    companion object {
        val Noop: MetricDetailsActions = object : MetricDetailsActions {}
    }
}
