package com.vayunmathur.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.health.Route
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.MetricDetailsActions
import com.vayunmathur.health.platform.MetricDetailsUiState
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Binds [HealthViewModel] and the back stack to the stateless [BarChartDetailsScreen]. */
@Composable
fun BarChartDetails(
    backStack: NavBackStack<Route>, viewModel: HealthViewModel, config: HealthMetricConfig
) {
    val dataState by viewModel.barChartData.collectAsState()
    val hasLoaded by viewModel.hasLoadedBarChart.collectAsState()
    // Read once: the screen compares the anchor date against it on every recomposition, and
    // a value that changes mid-composition would make the "next period" button flicker.
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    BarChartDetailsScreen(
        state = MetricDetailsUiState(config = config, today = today, data = dataState, hasLoaded = hasLoaded),
        actions = object : MetricDetailsActions {
            override fun loadBarChartData(
                config: HealthMetricConfig,
                anchorDate: LocalDate,
                selectedTab: Int,
            ) {
                viewModel.loadBarChartData(config, anchorDate, selectedTab)
            }

            override fun navigateUp() {
                backStack.pop()
            }
        },
    )
}
