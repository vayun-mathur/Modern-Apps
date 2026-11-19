package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBedtime
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconRain
import com.vayunmathur.library.ui.LazyListScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.components.MetricRow
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.health.platform.TodayActions
import com.vayunmathur.health.platform.TodayUiState
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.util.round

/**
 * The Today screen, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(state: TodayUiState, actions: TodayActions) {
    LazyListScaffold(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            item {
                ActivityRingsHero(
                    stepsToday = state.steps,
                    activeCaloriesToday = state.activeCalories,
                    mindfulnessToday = state.mindfulnessMinutes,
                )
            }

            // Last night
            item {
                DashboardSection(
                    title = stringResource(R.string.section_last_night),
                    accentColor = HealthColors.Sleep,
                ) {
                    val sleepValue = state.metrics.sleepMinutes?.let { formatSleep(it) } ?: "--"
                    MetricRow(
                        label = stringResource(R.string.metric_sleep),
                        value = sleepValue,
                        unit = "",
                        leadingIcon = { m, c -> IconBedtime(m, c) },
                        leadingTint = HealthColors.Sleep,
                        labelModifier = Modifier.sharedText("health-metric-label-SLEEP"),
                        onClick = { actions.openSleepDetails() },
                    )
                }
            }

            // Latest vitals
            item {
                DashboardSection(
                    title = stringResource(R.string.section_latest_vitals),
                    accentColor = HealthColors.Vitals,
                ) {
                    MetricRow(
                        label = stringResource(R.string.metric_heart_rate),
                        value = if (state.heartRateMax > 0L) "${state.heartRateMin}-${state.heartRateMax}" else "--",
                        unit = stringResource(R.string.unit_bpm),
                        leadingIcon = { m, c -> IconFavorite(m, c) },
                        leadingTint = colorFor(RecordType.HeartRate),
                        labelModifier = Modifier.sharedText("health-metric-label-HEART_RATE"),
                        onClick = { actions.openMetric(HealthMetricConfig.HEART_RATE) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_blood_pressure),
                        value = state.metrics.bloodPressure?.let { "${it.first.toInt()}/${it.second.toInt()}" } ?: "--",
                        unit = stringResource(R.string.label_blood_pressure_unit),
                        leadingIcon = { m, c -> IconFavorite(m, c) },
                        leadingTint = colorFor(RecordType.BloodPressure),
                        labelModifier = Modifier.sharedText("health-metric-label-BLOOD_PRESSURE"),
                        onClick = { actions.openMetric(HealthMetricConfig.BLOOD_PRESSURE) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_oxygen_saturation),
                        value = state.metrics.spo2?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_percent),
                        leadingIcon = { m, c -> IconFavorite(m, c) },
                        leadingTint = colorFor(RecordType.OxygenSaturation),
                        labelModifier = Modifier.sharedText("health-metric-label-OXYGEN_SATURATION"),
                        onClick = { actions.openMetric(HealthMetricConfig.OXYGEN_SATURATION) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_resting_heart_rate),
                        value = state.metrics.rhr?.toString() ?: "--",
                        unit = stringResource(R.string.unit_bpm),
                        leadingIcon = { m, c -> IconFavorite(m, c) },
                        leadingTint = colorFor(RecordType.RestingHeartRate),
                        labelModifier = Modifier.sharedText("health-metric-label-RESTING_HEART_RATE"),
                        onClick = { actions.openMetric(HealthMetricConfig.RESTING_HEART_RATE) },
                    )
                }
            }

            // Quick stats. Also carries what the standalone Activity page used to show, which is
            // why active energy, elevation, wheelchair pushes and exercise are here rather than
            // only in the rings above — each is the one route to its chart.
            item {
                DashboardSection(
                    title = stringResource(R.string.section_quick_stats),
                    accentColor = HealthColors.Activity,
                ) {
                    MetricRow(
                        label = stringResource(R.string.metric_steps),
                        value = state.steps.toString(),
                        unit = stringResource(R.string.metric_steps),
                        leadingIcon = { m, c -> IconDirectionsWalk(m, c) },
                        leadingTint = colorFor(RecordType.Steps),
                        labelModifier = Modifier.sharedText("health-metric-label-STEPS"),
                        onClick = { actions.openMetric(HealthMetricConfig.STEPS) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_active),
                        value = state.activeCalories.toString(),
                        unit = stringResource(R.string.unit_cal),
                        leadingIcon = { m, c -> IconFire(m, c) },
                        leadingTint = colorFor(RecordType.CaloriesActive),
                        labelModifier = Modifier.sharedText("health-metric-label-ACTIVE_CALORIES"),
                        onClick = { actions.openMetric(HealthMetricConfig.ACTIVE_CALORIES) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_distance),
                        value = state.distanceKm.round(2).toString(),
                        unit = stringResource(R.string.unit_km),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Distance),
                        labelModifier = Modifier.sharedText("health-metric-label-DISTANCE"),
                        onClick = { actions.openMetric(HealthMetricConfig.DISTANCE) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_floors),
                        value = state.floors.round(1).toString(),
                        unit = stringResource(R.string.unit_fl),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Floors),
                        labelModifier = Modifier.sharedText("health-metric-label-FLOORS"),
                        onClick = { actions.openMetric(HealthMetricConfig.FLOORS) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_elevation),
                        value = state.elevationMeters.round(1).toString(),
                        unit = stringResource(R.string.unit_m),
                        leadingIcon = { m, c -> IconLocationOn(m, c) },
                        leadingTint = colorFor(RecordType.Elevation),
                        labelModifier = Modifier.sharedText("health-metric-label-ELEVATION"),
                        onClick = { actions.openMetric(HealthMetricConfig.ELEVATION) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_wheelchair_pushes),
                        value = state.wheelchairPushes.toString(),
                        unit = stringResource(R.string.unit_pushes),
                        leadingIcon = { m, c -> IconDirectionsWalk(m, c) },
                        leadingTint = colorFor(RecordType.Wheelchair),
                        labelModifier = Modifier.sharedText("health-metric-label-WHEELCHAIR_PUSHES"),
                        onClick = { actions.openMetric(HealthMetricConfig.WHEELCHAIR_PUSHES) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_exercise_duration),
                        value = state.exerciseMinutes.toString(),
                        unit = stringResource(R.string.unit_min),
                        leadingIcon = { m, c -> IconDirectionsWalk(m, c) },
                        leadingTint = colorFor(RecordType.Exercise),
                        labelModifier = Modifier.sharedText("health-metric-label-EXERCISE"),
                        onClick = { actions.openExerciseDetails() },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_hydration),
                        value = state.hydrationMl.toInt().toString(),
                        unit = stringResource(R.string.unit_ml),
                        leadingIcon = { m, c -> IconRain(m, c) },
                        leadingTint = hydrationColor,
                        labelModifier = Modifier.sharedText("health-metric-label-HYDRATION"),
                        onClick = { actions.openMetric(HealthMetricConfig.HYDRATION) },
                    )
                }
            }
    }
}
