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
 * Configuration for different health metrics.
 * Biological markers (HRV, Breathing, etc) use Line Charts and Average-based aggregation.
 */
enum class HealthMetricConfig(
    val title: String,
    val recordType: RecordType,
    val unit: String,
    val dailyGoal: Double,
    val isLineChart: Boolean = false,
    val useDecimals: Boolean = false
) {
    ENERGY("Energy Burned", RecordType.CaloriesTotal, "cal", 2470.0),
    ACTIVE_CALORIES("Active Calories", RecordType.CaloriesActive, "cal", 500.0),
    BASAL_METABOLIC_RATE("Basal Metabolic Rate", RecordType.CaloriesBasal, "cal", 1800.0),
    STEPS("Steps", RecordType.Steps, "steps", 10000.0),
    WHEELCHAIR_PUSHES("Wheelchair Pushes", RecordType.Wheelchair, "pushes", 3000.0),
    DISTANCE("Distance", RecordType.Distance, "km", 5.0, useDecimals = true),
    ELEVATION("Elevation Gained", RecordType.Elevation, "m", 50.0, useDecimals = true),
    FLOORS("Floors Climbed", RecordType.Floors, "floors", 10.0, useDecimals = true),

    // Biological Metrics (Line Charts + Averages)
    BREATHING_RATE("Breathing Rate", RecordType.RespiratoryRate, "brpm", 16.0, isLineChart = true, useDecimals = true),
    RESTING_HEART_RATE("Resting Heart Rate", RecordType.RestingHeartRate, "bpm", 60.0, isLineChart = true),
    OXYGEN_SATURATION("Oxygen Saturation", RecordType.OxygenSaturation, "%", 95.0, isLineChart = true, useDecimals = true),
    HRV("Heart Rate Variability", RecordType.HeartRateVariabilityRmssd, "ms", 50.0, isLineChart = true, useDecimals = true)
}

data class MetricDashboardData(
    val totalValue: Double = 0.0,
    val dailyAverage: Double = 0.0,
    val chartData: List<Pair<String, Double?>> = emptyList(),
    val historyItems: List<HistoryItem> = emptyList(),
    val totalBarCount: Int = 0
)

data class HistoryItem(
    val label: String,
    val value: Double?,
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
    var selectedTab by remember { mutableIntStateOf(2) }
    val tabs = listOf("Day", "Week", "Month", "Year")

    var anchorDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }
    var dataState by remember { mutableStateOf(MetricDashboardData()) }

    val tz = TimeZone.currentSystemDefault()

    LaunchedEffect(selectedTab, anchorDate, config) {
        val (startTime, endTime, periodType) = when (selectedTab) {
            0 -> Triple(
                anchorDate.atStartOfDayIn(tz),
                anchorDate.atTime(23, 59, 59).toInstant(tz),
                HealthAPI.PeriodType.Hourly
            )
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

        val rawValues: List<Double?> = if (config.isLineChart) {
            HealthAPI.getListOfAverages(config.recordType, startTime, endTime, periodType)
        } else {
            HealthAPI.getListOfSums(config.recordType, startTime, endTime, periodType)
        }

        val mappedChart = rawValues.mapIndexed { index, value ->
            val label = when (selectedTab) {
                0 -> index.toString()
                1 -> startTime.plus(index.toLong(), DateTimeUnit.DAY, tz).toLocalDateTime(tz).dayOfWeek.name.take(3)
                else -> (index + 1).toString()
            }
            label to value
        }

        val history = rawValues.mapIndexed { index, value ->
            val label = when (selectedTab) {
                0 -> "Hour $index"
                1 -> startTime.plus(index.toLong(), DateTimeUnit.DAY, tz).toLocalDateTime(tz).dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
                2 -> "Day ${index + 1}"
                else -> "Week ${index + 1}"
            }
            HistoryItem(
                label = label,
                value = value,
                unit = config.unit,
                isGoalMet = (value ?: 0.0) >= config.dailyGoal,
                useDecimals = config.useDecimals
            )
        }.filter { it.value != null }.reversed() // Remove "No data" cards by filtering nulls

        val nonNullValues = rawValues.filterNotNull()
        val totalValue = nonNullValues.sum()
        val dailyAvg = if (config.isLineChart) {
            if (nonNullValues.isEmpty()) 0.0 else nonNullValues.average()
        } else {
            val divisor = rawValues.size.coerceAtLeast(1)
            totalValue / divisor
        }

        dataState = MetricDashboardData(
            totalValue = totalValue,
            dailyAverage = dailyAvg,
            chartData = mappedChart,
            historyItems = history,
            totalBarCount = rawValues.size
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
                        val avgString = if (config.useDecimals) {
                            String.format("%.1f", dataState.dailyAverage)
                        } else {
                            String.format("%,d", dataState.dailyAverage.toLong())
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
                            goalValue = config.dailyGoal,
                            lineColor = MaterialTheme.colorScheme.primary,
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
    goalValue: Double,
    lineColor: Color,
    goalColor: Color
) {
    if (data.all { it.second == null }) return

    val maxChartValue = (goalValue * 1.5f).coerceAtLeast(100.0)
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)

    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val spacing = width / (data.size - 1).coerceAtLeast(1)

            // Goal line
            val goalY = height - (goalValue.toFloat() / maxChartValue.toFloat() * height).coerceIn(0f, height)
            drawLine(color = goalColor, start = Offset(0f, goalY), end = Offset(width, goalY), strokeWidth = 1.dp.toPx())

            // Draw line segments
            var lastValidPoint: Offset? = null

            data.forEachIndexed { index, pair ->
                val value = pair.second
                val x = index * spacing

                if (value != null) {
                    val y = height - (value.toFloat() / maxChartValue.toFloat() * height).coerceIn(0f, height)
                    val currentPoint = Offset(x, y)

                    if (lastValidPoint != null) {
                        drawLine(
                            color = lineColor,
                            start = lastValidPoint!!,
                            end = currentPoint,
                            strokeWidth = 3.dp.toPx()
                        )
                    }

                    drawCircle(color = lineColor, radius = 3.dp.toPx(), center = currentPoint)
                    lastValidPoint = currentPoint
                }
            }
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
    val maxChartValue = (goalValue * 2f).coerceAtLeast(100f)
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

                    val valueString = if (item.useDecimals) {
                        String.format("%.1f", item.value)
                    } else {
                        String.format("%,d", item.value.toLong())
                    }

                    Text(
                        text = "$valueString ${item.unit}",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}