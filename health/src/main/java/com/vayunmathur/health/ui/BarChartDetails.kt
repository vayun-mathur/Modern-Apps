package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.Tuple4
import com.vayunmathur.library.util.round
import com.vayunmathur.library.util.toStringCommas
import com.vayunmathur.library.util.toStringDigits
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Configuration for health metrics.
 */
enum class HealthMetricConfig(
    @StringRes val titleRes: Int,
    val recordType: RecordType,
    val unit: String,
    val dailyGoal: Double,
    val secondaryGoal: Double? = null,
    val isLineChart: Boolean = false,
    val useDecimals: Boolean = false,
    val isDualSeries: Boolean = false
) {
    ENERGY(R.string.metric_energy_burned, RecordType.CaloriesTotal, "cal", 2470.0),
    ACTIVE_CALORIES(R.string.metric_active_calories, RecordType.CaloriesActive, "cal", 500.0),
    BASAL_METABOLIC_RATE(R.string.metric_basal_metabolic_rate, RecordType.CaloriesBasal, "cal", 1800.0),
    STEPS(R.string.metric_steps, RecordType.Steps, "steps", 10000.0),
    WHEELCHAIR_PUSHES(R.string.metric_wheelchair_pushes, RecordType.Wheelchair, "pushes", 3000.0),
    DISTANCE(R.string.metric_distance, RecordType.Distance, "km", 5.0, isLineChart = false, useDecimals = true),
    ELEVATION(R.string.metric_elevation_gained, RecordType.Elevation, "m", 50.0, isLineChart = false, useDecimals = true),
    FLOORS(R.string.metric_floors_climbed, RecordType.Floors, "floors", 10.0, isLineChart = false, useDecimals = true),
    HYDRATION(R.string.metric_hydration, RecordType.Hydration, "L", 2.5, isLineChart = false, useDecimals = true),

    // Biological & Medical (Line Charts)
    BLOOD_PRESSURE(R.string.metric_blood_pressure, RecordType.BloodPressure, "mmHg", 120.0, secondaryGoal = 80.0, isLineChart = true, isDualSeries = true),
    GLUCOSE(R.string.metric_blood_glucose, RecordType.BloodGlucose, "mg/dL", 100.0, isLineChart = true, useDecimals = true),
    VO2_MAX(R.string.metric_vo2_max, RecordType.Vo2Max, "ml/kg/min", 45.0, isLineChart = true, useDecimals = true),
    SKIN_TEMP(R.string.metric_skin_temp_variation, RecordType.SkinTemperature, "°C", 0.0, isLineChart = true, useDecimals = true),
    BREATHING_RATE(R.string.metric_breathing_rate, RecordType.RespiratoryRate, "brpm", 16.0, isLineChart = true, useDecimals = true),
    RESTING_HEART_RATE(R.string.metric_resting_heart_rate, RecordType.RestingHeartRate, "bpm", 60.0, isLineChart = true),
    OXYGEN_SATURATION(R.string.metric_oxygen_saturation, RecordType.OxygenSaturation, "%", 95.0, isLineChart = true, useDecimals = true),
    HRV(R.string.metric_heart_rate_variability, RecordType.HeartRateVariabilityRmssd, "ms", 50.0, isLineChart = true, useDecimals = true),
    HEART_RATE(R.string.metric_heart_rate, RecordType.HeartRate, "bpm", 100.0, isLineChart = true),

    // Physical Measurements
    HEIGHT(R.string.metric_height, RecordType.Height, "cm", 175.0, isLineChart = true, useDecimals = true),
    WEIGHT(R.string.metric_weight, RecordType.Weight, "kg", 75.0, isLineChart = true, useDecimals = true),
    BODY_FAT(R.string.metric_body_fat, RecordType.BodyFat, "%", 20.0, isLineChart = true, useDecimals = true),
    LEAN_BODY_MASS(R.string.metric_lean_body_mass, RecordType.LeanBodyMass, "kg", 60.0, isLineChart = true, useDecimals = true),
    BONE_MASS(R.string.metric_bone_mass, RecordType.BoneMass, "kg", 3.0, isLineChart = true, useDecimals = true),
    BODY_WATER_MASS(R.string.metric_body_water_mass, RecordType.BodyWaterMass, "kg", 45.0, isLineChart = true, useDecimals = true)
}

data class MetricDashboardData(
    val totalValue: Double = 0.0,
    val dailyAverage: Double = 0.0,
    val secondaryAverage: Double? = null,
    val chartData: List<Pair<String, Double?>> = emptyList(),
    val secondaryChartData: List<Pair<String, Double?>>? = null,
    val primaryRange: ClosedFloatingPointRange<Double>? = null,
    val historyItems: List<HistoryItem> = emptyList(),
    val totalBarCount: Int = 0
)

data class HistoryItem(
    val label: String,
    val value: Double,
    val secondaryValue: Double? = null,
    val unit: String,
    val isGoalMet: Boolean,
    val useDecimals: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarChartDetails(
    backStack: NavBackStack<Route>,
    config: HealthMetricConfig
) {
    var selectedTab by remember { mutableIntStateOf(if(config == HealthMetricConfig.HEART_RATE) 0 else 1) }
    val tabs = listOf(
        stringResource(R.string.tab_day),
        stringResource(R.string.tab_week),
        stringResource(R.string.tab_month),
        stringResource(R.string.tab_year)
    )

    var anchorDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }
    var dataState by remember { mutableStateOf(MetricDashboardData()) }

    val tz = TimeZone.currentSystemDefault()

    val dayLabelFormat = stringResource(R.string.history_day_label)
    val monthLabelFormat = stringResource(R.string.history_month_label)

    LaunchedEffect(selectedTab, anchorDate, config) {
        val (startDate, endDate, periodType, periodType2) = when (selectedTab) {
            0 -> Tuple4(anchorDate, anchorDate.plus(1, DateTimeUnit.DAY), HealthAPI.PeriodType.Hourly, HealthAPI.PeriodType.Hourly)
            1 -> {
                val start = anchorDate.minus((anchorDate.dayOfWeek.ordinal+1)%7, DateTimeUnit.DAY)
                Tuple4(start, start.plus(7, DateTimeUnit.DAY), HealthAPI.PeriodType.Daily, HealthAPI.PeriodType.Daily)
            }
            2 -> {
                val start = LocalDate(anchorDate.year, anchorDate.month, 1)
                val end = start.plus(1, DateTimeUnit.MONTH)
                Tuple4(start, end, HealthAPI.PeriodType.Daily, HealthAPI.PeriodType.Weekly)
            }
            else -> {
                val start = LocalDate(anchorDate.year, 1, 1)
                val end = start.plus(1, DateTimeUnit.YEAR)
                Tuple4(start, end, HealthAPI.PeriodType.Monthly, HealthAPI.PeriodType.Monthly)
            }
        }
        val startTime = startDate.atStartOfDayIn(tz)
        val endTime = endDate.atStartOfDayIn(tz)

        val endTimeNow = if(Clock.System.now() < endTime) Clock.System.now() else endTime

        // Both functions now return List<Pair<Double?, Double?>>
        val rawPairs = if (config.isLineChart) {
            HealthAPI.getListOfAverages(config.recordType, startTime, endTimeNow, periodType)
        } else {
            HealthAPI.getListOfSums(config.recordType, startTime, endTimeNow, periodType)
        }
        val rawPairsHistory = if (config.isLineChart) {
            HealthAPI.getListOfAverages(config.recordType, startTime, endTimeNow, periodType2)
        } else {
            HealthAPI.getListOfSums(config.recordType, startTime, endTimeNow, periodType2)
        }

        val mappedChart = rawPairs.mapIndexed { i, p -> i.toString() to p.second }
        val mappedSecondaryChart = if (config.isDualSeries) {
            rawPairs.mapIndexed { i, p -> i.toString() to p.third }
        } else null

        val history = if(selectedTab != 0) rawPairsHistory.mapIndexed { index, triple ->
            val label = when (selectedTab) {
                0 -> ""
                1 -> startTime.plus(index.toLong(), DateTimeUnit.DAY, tz).toLocalDateTime(tz).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
                2 -> String.format(dayLabelFormat, index + 1)
                else -> String.format(monthLabelFormat, index + 1)
            }
            HistoryItem(
                label = label,
                value = triple.second,
                secondaryValue = if (config.isDualSeries) triple.third else null,
                unit = config.unit,
                isGoalMet = triple.second >= config.dailyGoal,
                useDecimals = config.useDecimals
            )
        }.reversed() else listOf()

        val nonNullPrimary = history.map { it.value }
        val nonNullSecondary = if (config.isDualSeries) history.mapNotNull { it.secondaryValue } else emptyList()

        dataState = MetricDashboardData(
            totalValue = nonNullPrimary.sum(),
            dailyAverage = if (nonNullPrimary.isEmpty()) 0.0 else (if(selectedTab == 0) nonNullPrimary.sum() else nonNullPrimary.average()),
            secondaryAverage = if (nonNullSecondary.isEmpty()) null else (if(selectedTab == 0) nonNullSecondary.sum() else nonNullSecondary.average()),
            chartData = mappedChart,
            secondaryChartData = mappedSecondaryChart,
            historyItems = history,
            totalBarCount = rawPairs.size,
            primaryRange = mappedChart.minOfOrNull { it.second }?.rangeTo(mappedChart.maxOf { it.second })
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(config.titleRes)) },
                navigationIcon = { IconNavigation(backStack) }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if(config != HealthMetricConfig.HEART_RATE) {
                SecondaryTabRow(selectedTabIndex = selectedTab, divider = {}) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    title,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
            ) {
                item {
                    val weekOfFormat = stringResource(R.string.week_of)
                    val headerLabel = when (selectedTab) {
                        0 -> "${anchorDate.month.name} ${anchorDate.day}, ${anchorDate.year}"
                        1 -> String.format(weekOfFormat, anchorDate.minus((anchorDate.dayOfWeek.ordinal+1)%7, DateTimeUnit.DAY))
                        2 -> "${anchorDate.month.name} ${anchorDate.year}"
                        else -> anchorDate.year.toString()
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                            anchorDate = when(selectedTab) {
                                0 -> anchorDate.minus(1, DateTimeUnit.DAY)
                                1 -> anchorDate.minus(1, DateTimeUnit.WEEK)
                                2 -> anchorDate.minus(1, DateTimeUnit.MONTH)
                                else -> anchorDate.minus(1, DateTimeUnit.YEAR)
                            }
                        }) {
                            Icon(painterResource(R.drawable.baseline_arrow_back_24), stringResource(R.string.nav_prev))
                        }

                        Text(headerLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 140.dp), textAlign = TextAlign.Center)

                        val nextDate = when(selectedTab) {
                            0 -> anchorDate.plus(1, DateTimeUnit.DAY)
                            1 -> anchorDate.plus(1, DateTimeUnit.WEEK)
                            2 -> anchorDate.plus(1, DateTimeUnit.MONTH)
                            else -> anchorDate.plus(1, DateTimeUnit.YEAR)
                        }

                        IconButton(onClick = {
                            anchorDate = when(selectedTab) {
                                0 -> anchorDate.plus(1, DateTimeUnit.DAY)
                                1 -> anchorDate.plus(1, DateTimeUnit.WEEK)
                                2 -> anchorDate.plus(1, DateTimeUnit.MONTH)
                                else -> anchorDate.plus(1, DateTimeUnit.YEAR)
                            }
                        }, enabled = nextDate <= Clock.System.todayIn(TimeZone.currentSystemDefault())) {
                            Icon(painterResource(R.drawable.outline_arrow_forward_24), stringResource(R.string.nav_next))
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.Bottom) {
                        val formatVal = { v: Double -> if (config.useDecimals) v.round(1).toString() else v.toLong().toStringCommas() }
                        val avgString = if(config == HealthMetricConfig.HEART_RATE) {
                            "${formatVal(dataState.primaryRange?.start ?: 0.0)} - ${formatVal(dataState.primaryRange?.endInclusive ?: 0.0)}"
                        } else if (dataState.secondaryAverage != null && config.isDualSeries) {
                            "${formatVal(dataState.dailyAverage)}/${formatVal(dataState.secondaryAverage!!)}"
                        } else {
                            formatVal(dataState.dailyAverage)
                        }

                        Text(
                            text = avgString,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Light
                        )
                        val perDayAvg = stringResource(R.string.per_day_avg)
                        Text(
                            text = " ${config.unit}${if (selectedTab != 0 && !config.isLineChart) perDayAvg else ""}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                            color = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(32.dp))
                    if (config.isLineChart) {
                        GenericLineChart(
                            data = dataState.chartData,
                            secondaryData = dataState.secondaryChartData,
                            goalValue = config.dailyGoal,
                            secondaryGoal = config.secondaryGoal,
                            lineColor = MaterialTheme.colorScheme.primary,
                            secondaryLineColor = MaterialTheme.colorScheme.tertiary,
                            goalColor = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        GenericBarChart(
                            data = dataState.chartData.map { it.first to (it.second?.toLong() ?: 0L) },
                            totalBarCount = dataState.totalBarCount,
                            goalValue = config.dailyGoal.toLong(),
                            barColor = MaterialTheme.colorScheme.primary,
                            goalColor = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                }

                itemsIndexed(dataState.historyItems) { idx, item ->
                    Card(Modifier.padding(vertical = 2.dp), shape = verticalSegmentedCardShape(idx, dataState.historyItems.size)) {
                        ListItem({
                            Text(item.label)
                        }, trailingContent = {
                            Row {
                                if(item.isGoalMet) {
                                    IconCheck()
                                }
                                val format = { v: Double -> if (item.useDecimals) v.toStringDigits(1) else v.toLong().toStringCommas() }
                                val valueString = if (item.secondaryValue != null) "${format(item.value)}/${format(item.secondaryValue)}" else format(item.value)
                                Text("$valueString ${item.unit}")
                            }
                        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            }
        }
    }
}

@Composable
fun GenericLineChart(
    data: List<Pair<String, Double?>>,
    secondaryData: List<Pair<String, Double?>>? = null,
    goalValue: Double,
    secondaryGoal: Double? = null,
    lineColor: Color,
    secondaryLineColor: Color,
    goalColor: Color
) {
    if (data.all { it.second == null }) return

    val allValues = (data.mapNotNull { it.second } + (secondaryData?.mapNotNull { it.second } ?: emptyList()))
    val maxChartValue = (allValues.maxOrNull() ?: goalValue).coerceAtLeast(goalValue * 1.2).coerceAtLeast(10.0)
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)

    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val spacing = width / (data.size - 1).coerceAtLeast(1)

            val drawSeries = { series: List<Pair<String, Double?>>, color: Color ->
                var lastValidPoint: Offset? = null
                series.forEachIndexed { index, pair ->
                    val value = pair.second
                    val x = index * spacing
                    if (value != null) {
                        val y = height - (value.toFloat() / maxChartValue.toFloat() * height).coerceIn(0f, height)
                        val currentPoint = Offset(x, y)
                        if (lastValidPoint != null) {
                            drawLine(color = color, start = lastValidPoint, end = currentPoint, strokeWidth = 3.dp.toPx())
                        }
                        drawCircle(color = color, radius = 3.dp.toPx(), center = currentPoint)
                        lastValidPoint = currentPoint
                    }
                }
            }

            // Goal lines
            val drawGoal = { goal: Double, color: Color ->
                val gy = height - (goal.toFloat() / maxChartValue.toFloat() * height).coerceIn(0f, height)
                drawLine(color = color, start = Offset(0f, gy), end = Offset(width, gy), strokeWidth = 1.dp.toPx())
            }

            drawGoal(goalValue, goalColor)
            secondaryGoal?.let { drawGoal(it, goalColor.copy(alpha = 0.5f)) }

            drawSeries(data, lineColor)
            secondaryData?.let { drawSeries(it, secondaryLineColor) }
        }

        Text(text = maxChartValue.toLong().toStringCommas(), color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
        Text(text = goalValue.toLong().toStringCommas(), color = lineColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(top = 40.dp))
        Text(text = "0", color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
fun GenericBarChart(
    data: List<Pair<String, Long>>,
    totalBarCount: Int,
    goalValue: Long,
    barColor: Color,
    goalColor: Color
) {
    val maxValueFound = data.maxOfOrNull { it.second } ?: 0L
    val maxChartValue = (maxValueFound.toFloat() * 1.2f).coerceAtLeast(goalValue * 1.2f).coerceAtLeast(10f)
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)

    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val barWidth = 6.dp.toPx()
            val spacing = (width - (totalBarCount * barWidth)) / (totalBarCount + 1).coerceAtLeast(1)

            val goalY = height - (goalValue.toFloat() / maxChartValue * height).coerceIn(0f, height)
            drawLine(color = goalColor, start = Offset(0f, goalY), end = Offset(width, goalY), strokeWidth = 1.dp.toPx())

            data.forEachIndexed { index, pair ->
                val barHeight = (pair.second.toFloat() / maxChartValue * height).coerceIn(4f, height)
                val x = spacing + index * (barWidth + spacing)
                val y = height - barHeight

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
        }

        Text(text = maxChartValue.toLong().toStringCommas(), color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
        Text(text = goalValue.toStringCommas(), color = barColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(top = 40.dp))
        Text(text = "0", color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
    }
}