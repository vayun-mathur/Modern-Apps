package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.platform.MainPageMetrics
import com.vayunmathur.health.platform.MetricDetailsActions
import com.vayunmathur.health.platform.MetricDetailsUiState
import com.vayunmathur.health.platform.NutritionActions
import com.vayunmathur.health.platform.NutritionUiState
import com.vayunmathur.health.platform.TodayActions
import com.vayunmathur.health.platform.TodayUiState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconBodySystem
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconMedicalServices
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-shaped: exercises the detail screens at a width where list and detail sit side by side. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * A fixed Saturday. Every date the chart screen derives — the week range in the header,
 * whether the "next period" arrow is enabled — hangs off this, so pinning it is what keeps
 * the rendered images identical from one run to the next.
 */
private val TODAY = LocalDate(2026, 1, 17)

/**
 * Store listing images for `:health`, rendered from Compose previews instead of from an
 * instrumented test on a device.
 *
 * `./gradlew :health:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/health/`, where `release.sh` picks them up.
 *
 * Four things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames embed
 *    the function name, so `Preview1Today`/`Preview2Nutrition`/... sort into listing order.
 *    Renumber the functions if you reorder the listing.
 *  - Everything must be a literal. Health Connect, the Room cache and the bundled food
 *    database do not exist here, so the state below is the whole input — which is also what
 *    makes the output reproducible from a clean checkout. Dates come from [TODAY] rather
 *    than the clock for the same reason.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in Studio
 *    but is not collected as a screenshot test, and the build fails with the unhelpful "did
 *    not discover any tests".
 *  - The previews must be members of a class, not top-level functions. Top-level previews
 *    land in a synthetic `…Kt` facade that the screenshot engine silently skips.
 *
 * Rendering goes through the app's real [DynamicTheme] with `darkTheme = true`, matching the
 * `cmd uimode night yes` the old on-device generator used. Material You sources its palette
 * from the device wallpaper, which does not exist here, so these render with the fallback
 * scheme rather than a user's actual accent colour.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-today", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Today() {
        ChromePreview(Route.Today) {
            TodayScreen(
                state = TodayUiState(
                    steps = 8_432L,
                    activeCalories = 412L,
                    mindfulnessMinutes = 15L,
                    distanceKm = 6.12,
                    floors = 7.0,
                    hydrationMl = 1850.0,
                    heartRateMin = 52L,
                    heartRateMax = 141L,
                    metrics = MainPageMetrics(
                        spo2 = 98.0,
                        rhr = 58L,
                        bloodPressure = 118.0 to 76.0,
                        sleepMinutes = 447L,
                    ),
                ),
                actions = TodayActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-nutrition", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Nutrition() {
        ChromePreview(Route.NutritionDetails) {
            NutritionScreen(
                state = NutritionUiState(
                    totals = NutritionData(
                        protein = 96.4,
                        carbohydrates = 214.7,
                        fat = 61.3,
                        calories = 1740.0,
                    ),
                    mealCount = 3,
                    mealCalories = 1740.0,
                ),
                actions = NutritionActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-steps", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Steps() {
        // A week of steps: seven daily sums, newest first in the history list, which is the
        // shape HealthViewModel.loadBarChartData produces for the "Week" tab.
        val days = listOf(
            "Sun" to 6_240.0,
            "Mon" to 11_820.0,
            "Tue" to 9_450.0,
            "Wed" to 13_100.0,
            "Thu" to 8_760.0,
            "Fri" to 12_340.0,
            "Sat" to 10_580.0,
        )
        val fullNames = listOf(
            "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
        )
        ChromePreview(Route.BarChartDetails(HealthMetricConfig.STEPS)) {
            BarChartDetailsScreen(
                state = MetricDetailsUiState(
                    config = HealthMetricConfig.STEPS,
                    today = TODAY,
                    data = MetricDashboardData(
                        totalValue = 72_290.0,
                        dailyAverage = 10_327.14,
                        chartData = days,
                        historyItems = days.mapIndexed { index, (_, value) ->
                            HistoryItem(
                                label = fullNames[index],
                                value = value,
                                unit = HealthMetricConfig.STEPS.unit,
                                isGoalMet = value >= HealthMetricConfig.STEPS.dailyGoal,
                                useDecimals = false,
                            )
                        }.reversed(),
                        totalBarCount = days.size,
                    ),
                ),
                actions = MetricDetailsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-heart-rate", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4HeartRate() {
        // Heart rate is the one metric with no period tabs: it is always the hourly line for
        // a single day, so this is the "Day" shape — 24 averages and no history list.
        val hourly = listOf(
            56.0, 54.0, 53.0, 52.0, 54.0, 57.0, 62.0, 71.0,
            78.0, 74.0, 76.0, 80.0, 84.0, 79.0, 77.0, 88.0,
            112.0, 134.0, 141.0, 96.0, 82.0, 74.0, 66.0, 60.0,
        )
        ChromePreview(Route.BarChartDetails(HealthMetricConfig.HEART_RATE)) {
            BarChartDetailsScreen(
                state = MetricDetailsUiState(
                    config = HealthMetricConfig.HEART_RATE,
                    today = TODAY,
                    data = MetricDashboardData(
                        totalValue = 1_860.0,
                        dailyAverage = 1_860.0,
                        chartData = hourly.mapIndexed { hour, bpm ->
                            (if (hour % 6 == 0) hourLabel(hour) else "") to bpm
                        },
                        primaryRange = 52.0..141.0,
                        totalBarCount = hourly.size,
                    ),
                ),
                actions = MetricDetailsActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5Expanded() {
        // The detail screen at desktop width: the week-of-steps chart a metric row opens.
        // The medical lists take the ViewModel directly and cannot render here, so the
        // preview-splittable chart screen stands in for the detail pane.
        val days = listOf(
            "Sun" to 6_240.0,
            "Mon" to 11_820.0,
            "Tue" to 9_450.0,
            "Wed" to 13_100.0,
            "Thu" to 8_760.0,
            "Fri" to 12_340.0,
            "Sat" to 10_580.0,
        )
        val fullNames = listOf(
            "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
        )
        ChromePreview(Route.BarChartDetails(HealthMetricConfig.STEPS)) {
            BarChartDetailsScreen(
                state = MetricDetailsUiState(
                    config = HealthMetricConfig.STEPS,
                    today = TODAY,
                    data = MetricDashboardData(
                        totalValue = 72_290.0,
                        dailyAverage = 10_327.14,
                        chartData = days,
                        historyItems = days.mapIndexed { index, (_, value) ->
                            HistoryItem(
                                label = fullNames[index],
                                value = value,
                                unit = HealthMetricConfig.STEPS.unit,
                                isGoalMet = value >= HealthMetricConfig.STEPS.dailyGoal,
                                useDecimals = false,
                            )
                        }.reversed(),
                        totalBarCount = days.size,
                    ),
                ),
                actions = MetricDetailsActions.Noop,
            )
        }
    }

    /**
     * The app's real tab chrome around preview screen content: the same four
     * [BottomBarItem]s and unconditional [MainNavigation] host the activity
     * declares, with a literal back stack for the shown route. Body and Medical
     * need their ViewModels, so their entries show an empty page (never visible —
     * the stack stays on the previewed route).
     */
    @Composable
    private fun ChromePreview(route: Route, content: @Composable () -> Unit) {
        DynamicTheme(darkTheme = true) {
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides rememberNavigationEventDispatcherOwner(
                    parent = null,
                ),
            ) {
                val backStack = rememberNavBackStack(route)
                MainNavigation(
                    backStack = backStack,
                    bottomBar = {
                        BottomNavBar(
                            backStack = backStack,
                            pages = listOf(
                                BottomBarItem("Today", Route.Today) { IconFavorite() },
                                BottomBarItem("Nutrition", Route.NutritionDetails) { IconFire() },
                                BottomBarItem("Body", Route.Body) { IconBodySystem() },
                                BottomBarItem("Medical", Route.Medical) { IconMedicalServices() },
                            ),
                            currentPage = backStack.last(),
                        )
                    },
                ) {
                    entry<Route.Today>(metadata = SiblingPage()) { content() }
                    entry<Route.NutritionDetails>(metadata = SiblingPage()) { content() }
                    entry<Route.BarChartDetails>(metadata = SiblingPage()) { content() }
                    entry<Route.Body>(metadata = SiblingPage()) { Box(Modifier.fillMaxSize()) }
                    entry<Route.Medical>(metadata = SiblingPage()) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
    }
}

/**
 * The x-axis label the ViewModel would produce for a whole hour. Spelled out here rather
 * than calling the app's formatter so the axis reads the same whatever locale the renderer
 * happens to default to.
 */
private fun hourLabel(hour: Int): String = when (hour) {
    0 -> "12 AM"
    12 -> "12 PM"
    in 1..11 -> "$hour AM"
    else -> "${hour - 12} PM"
}
