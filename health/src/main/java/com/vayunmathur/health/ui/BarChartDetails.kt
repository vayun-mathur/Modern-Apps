package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.health.HealthAPI
import com.vayunmathur.health.Route
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.health.R
import com.vayunmathur.health.database.RecordType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Configuration for health metrics.
 */
enum class HealthMetricConfig(
    val title: String,
    val recordType: RecordType,
    val unit: String,
    val dailyGoal: Double,
    val secondaryGoal: Double? = null,
    val isLineChart: Boolean = false,
    val useDecimals: Boolean = false,
    val isDualSeries: Boolean = false
) {
    ENERGY("Energy Burned", RecordType.CaloriesTotal, "cal", 2470.0),
    ACTIVE_CALORIES("Active Calories", RecordType.CaloriesActive, "cal", 500.0),
    BASAL_METABOLIC_RATE("Basal Metabolic Rate", RecordType.CaloriesBasal, "cal", 1800.0),
    STEPS("Steps", RecordType.Steps, "steps", 10000.0),
    WHEELCHAIR_PUSHES("Wheelchair Pushes", RecordType.Wheelchair, "pushes", 3000.0),
    DISTANCE("Distance", RecordType.Distance, "km", 5.0, isLineChart = false, useDecimals = true),
    ELEVATION("Elevation Gained", RecordType.Elevation, "m", 50.0, isLineChart = false, useDecimals = true),
    FLOORS("Floors Climbed", RecordType.Floors, "floors", 10.0, isLineChart = false, useDecimals = true),
    HYDRATION("Hydration", RecordType.Hydration, "L", 2.5, isLineChart = false, useDecimals = true),

    // Biological & Medical (Line Charts)
    BLOOD_PRESSURE("Blood Pressure", RecordType.BloodPressure, "mmHg", 120.0, secondaryGoal = 80.0, isLineChart = true, isDualSeries = true),
    GLUCOSE("Blood Glucose", RecordType.BloodGlucose, "mg/dL", 100.0, isLineChart = true, useDecimals = true),
    VO2_MAX("VO2 Max", RecordType.Vo2Max, "ml/kg/min", 45.0, isLineChart = true, useDecimals = true),
    SKIN_TEMP("Skin Temp Variation", RecordType.SkinTemperature, "°C", 0.0, isLineChart = true, useDecimals = true),
    BREATHING_RATE("Breathing Rate", RecordType.RespiratoryRate, "brpm", 16.0, isLineChart = true, useDecimals = true),
    RESTING_HEART_RATE("Resting Heart Rate", RecordType.RestingHeartRate, "bpm", 60.0, isLineChart = true),
    OXYGEN_SATURATION("Oxygen Saturation", RecordType.OxygenSaturation, "%", 95.0, isLineChart = true, useDecimals = true),
    HRV("Heart Rate Variability", RecordType.HeartRateVariabilityRmssd, "ms", 50.0, isLineChart = true, useDecimals = true),

    // Physical Measurements
    HEIGHT("Height", RecordType.Height, "cm", 175.0, isLineChart = true, useDecimals = true),
    WEIGHT("Weight", RecordType.Weight, "kg", 75.0, isLineChart = true, useDecimals = true),
    BODY_FAT("Body Fat", RecordType.BodyFat, "%", 20.0, isLineChart = true, useDecimals = true),
    LEAN_BODY_MASS("Lean Body Mass", RecordType.LeanBodyMass, "kg", 60.0, isLineChart = true, useDecimals = true),
    BONE_MASS("Bone Mass", RecordType.BoneMass, "kg", 3.0, isLineChart = true, useDecimals = true),
    BODY_WATER_MASS("Body Water Mass", RecordType.BodyWaterMass, "kg", 45.0, isLineChart = true, useDecimals = true)
}

data class MetricDashboardData(
    val totalValue: Double = 0.0,
    val dailyAverage: Double = 0.0,
    val secondaryAverage: Double? = null,
    val chartData: List<Pair<String, Double?>> = emptyList(),
    val secondaryChartData: List<Pair<String, Double?>>? = null,
    val historyItems: List<HistoryItem> = emptyList(),
    val totalBarCount: Int = 0
)

data class HistoryItem(
    val label: String,
    val value: Double?,
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
    var selectedTab by remember { mutableIntStateOf(1) }
    val tabs = listOf("Day", "Week", "Month", "Year")

    var anchorDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }
    var dataState by remember { mutableStateOf(MetricDashboardData()) }

    val tz = TimeZone.currentSystemDefault()

    LaunchedEffect(selectedTab, anchorDate, config) {
        val (startTime, endTime, periodType) = when (selectedTab) {
            0 -> Triple(anchorDate.atStartOfDayIn(tz), anchorDate.atTime(23, 59, 59).toInstant(tz), HealthAPI.PeriodType.Hourly)
            1 -> {
                val start = anchorDate.minus(anchorDate.dayOfWeek.ordinal, DateTimeUnit.DAY)
                Triple(start.atStartOfDayIn(tz), start.plus(7, DateTimeUnit.DAY).atStartOfDayIn(tz), HealthAPI.PeriodType.Daily)
            }
            2 -> {
                val start = LocalDate(anchorDate.year, anchorDate.month, 1)
                val end = start.plus(1, DateTimeUnit.MONTH)
                Triple(start.atStartOfDayIn(tz), end.atStartOfDayIn(tz), HealthAPI.PeriodType.Daily)
            }
            else -> {
                val start = LocalDate(anchorDate.year, 1, 1)
                val end = start.plus(1, DateTimeUnit.YEAR)
                Triple(start.atStartOfDayIn(tz), end.atStartOfDayIn(tz), HealthAPI.PeriodType.Weekly)
            }
        }

        // Both functions now return List<Pair<Double?, Double?>>
        val rawPairs = if (config.isLineChart) {
            HealthAPI.getListOfAverages(config.recordType, startTime, endTime, periodType)
        } else {
            HealthAPI.getListOfSums(config.recordType, startTime, endTime, periodType)
        }

        val mappedChart = rawPairs.mapIndexed { i, p -> i.toString() to p.first }
        val mappedSecondaryChart = if (config.isDualSeries) {
            rawPairs.mapIndexed { i, p -> i.toString() to p.second }
        } else null

        val history = rawPairs.mapIndexed { index, pair ->
            val label = when (selectedTab) {
                0 -> "Hour $index"
                1 -> startTime.plus(index.toLong(), DateTimeUnit.DAY, tz).toLocalDateTime(tz).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
                2 -> "Day ${index + 1}"
                else -> "Week ${index + 1}"
            }
            HistoryItem(
                label = label,
                value = pair.first,
                secondaryValue = if (config.isDualSeries) pair.second else null,
                unit = config.unit,
                isGoalMet = (pair.first ?: 0.0) >= config.dailyGoal,
                useDecimals = config.useDecimals
            )
        }.filter { it.value != null }.reversed()

        val nonNullPrimary = rawPairs.mapNotNull { it.first }
        val nonNullSecondary = if (config.isDualSeries) rawPairs.mapNotNull { it.second } else emptyList()

        dataState = MetricDashboardData(
            totalValue = nonNullPrimary.sum(),
            dailyAverage = if (nonNullPrimary.isEmpty()) 0.0 else (if(selectedTab == 0) nonNullPrimary.sum() else nonNullPrimary.average()),
            secondaryAverage = if (nonNullSecondary.isEmpty()) null else (if(selectedTab == 0) nonNullSecondary.sum() else nonNullSecondary.average()),
            chartData = mappedChart,
            secondaryChartData = mappedSecondaryChart,
            historyItems = history,
            totalBarCount = rawPairs.size
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config.title) },
                navigationIcon = { IconNavigation(backStack) }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab, divider = {}) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
            ) {
                item {
                    val headerLabel = when (selectedTab) {
                        0 -> "${anchorDate.month.name} ${anchorDate.dayOfMonth}, ${anchorDate.year}"
                        1 -> "Week of ${anchorDate.minus(anchorDate.dayOfWeek.ordinal, DateTimeUnit.DAY)}"
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
                            Icon(painterResource(R.drawable.baseline_arrow_back_24), "Prev")
                        }

                        Text(headerLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 140.dp), textAlign = TextAlign.Center)

                        IconButton(onClick = {
                            anchorDate = when(selectedTab) {
                                0 -> anchorDate.plus(1, DateTimeUnit.DAY)
                                1 -> anchorDate.plus(1, DateTimeUnit.WEEK)
                                2 -> anchorDate.plus(1, DateTimeUnit.MONTH)
                                else -> anchorDate.plus(1, DateTimeUnit.YEAR)
                            }
                        }) {
                            Icon(painterResource(R.drawable.outline_arrow_forward_24), "Next")
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.Bottom) {
                        val formatVal = { v: Double -> if (config.useDecimals) String.format("%.1f", v) else String.format("%,d", v.toLong()) }
                        val avgString = if (dataState.secondaryAverage != null && config.isDualSeries) {
                            "${formatVal(dataState.dailyAverage)}/${formatVal(dataState.secondaryAverage!!)}"
                        } else {
                            formatVal(dataState.dailyAverage)
                        }

                        Text(
                            text = avgString,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Light
                        )
                        Text(
                            text = " ${config.unit}${if (selectedTab != 0 && !config.isLineChart) " per day (avg)" else ""}",
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
                }

                items(dataState.historyItems) { item ->
                    HistoryCard(item)
                    Spacer(Modifier.height(8.dp))
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
                            drawLine(color = color, start = lastValidPoint!!, end = currentPoint, strokeWidth = 3.dp.toPx())
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

        Text(text = String.format("%,d", maxChartValue.toLong()), color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
        Text(text = String.format("%,d", goalValue.toLong()), color = lineColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(top = 40.dp))
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

        Text(text = String.format("%,d", maxChartValue.toLong()), color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
        Text(text = String.format("%,d", goalValue), color = barColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(top = 40.dp))
        Text(text = "0", color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
fun HistoryCard(item: HistoryItem) {
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(item.label)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.value != null) {
                    if (item.isGoalMet) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_check_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    val format = { v: Double -> if (item.useDecimals) String.format("%.1f", v) else String.format("%,d", v.toLong()) }
                    val valueString = if (item.secondaryValue != null) "${format(item.value)}/${format(item.secondaryValue)}" else format(item.value)

                    Text(
                        text = "$valueString ${item.unit}",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}