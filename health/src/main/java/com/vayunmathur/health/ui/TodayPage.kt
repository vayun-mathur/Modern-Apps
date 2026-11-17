package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBedtime
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconRain
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.components.ActivityRingsTrio
import com.vayunmathur.health.ui.components.MetricRow
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.MainPageMetrics
import com.vayunmathur.health.platform.TodayActions
import com.vayunmathur.health.platform.TodayUiState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.util.round
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Binds [HealthViewModel] and the back stack to the stateless [TodayScreen]. */
@Composable
fun TodayPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val today = now.date

    val dayRange = remember(today) {
        val start = today.atStartOfDayIn(TimeZone.currentSystemDefault())
        start to start.plus(24.hours)
    }
    val dayStart = dayRange.first
    val dayEnd = dayRange.second

    val stepsToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Steps, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)
    val activeCaloriesToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.CaloriesActive, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)
    val mindfulnessToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Mindfulness, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)
    val distanceToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Distance, dayStart, dayEnd)
    }.collectAsState(0.0)
    val floorsToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Floors, dayStart, dayEnd)
    }.collectAsState(0.0)
    // Hydration records store liters; TodayUiState.hydrationMl / unit_ml need milliliters.
    val hydrationToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Hydration, dayStart, dayEnd).map { it * 1000.0 }
    }.collectAsState(0.0)
    val hrMax by remember(dayStart, dayEnd) {
        viewModel.maxInRange(RecordType.HeartRate, dayStart, dayEnd).map { it?.toLong() ?: 0L }
    }.collectAsState(0L)
    val hrMin by remember(dayStart, dayEnd) {
        viewModel.minInRange(RecordType.HeartRate, dayStart, dayEnd).map { it?.toLong() ?: 0L }
    }.collectAsState(0L)
    val elevationToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Elevation, dayStart, dayEnd)
    }.collectAsState(0.0)
    val wheelchairToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Wheelchair, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)
    val exerciseToday by remember(dayStart, dayEnd) {
        viewModel.sumInRange(RecordType.Exercise, dayStart, dayEnd).map { it.toLong() }
    }.collectAsState(0L)

    val metrics: MainPageMetrics by viewModel.mainPageMetrics.collectAsState()

    LaunchedEffect(today) {
        viewModel.loadMainPageMetrics()
    }

    TodayScreen(
        state = TodayUiState(
            steps = stepsToday,
            activeCalories = activeCaloriesToday,
            mindfulnessMinutes = mindfulnessToday,
            distanceKm = distanceToday,
            floors = floorsToday,
            hydrationMl = hydrationToday,
            heartRateMin = hrMin,
            heartRateMax = hrMax,
            elevationMeters = elevationToday,
            wheelchairPushes = wheelchairToday,
            exerciseMinutes = exerciseToday,
            metrics = metrics,
        ),
        actions = object : TodayActions {
            override fun openSleepDetails() {
                backStack.add(Route.SleepDetails)
            }

            override fun openExerciseDetails() {
                backStack.add(Route.ExerciseDetails)
            }

            override fun openMetric(config: HealthMetricConfig) {
                backStack.add(Route.BarChartDetails(config))
            }
        },
    )
}

