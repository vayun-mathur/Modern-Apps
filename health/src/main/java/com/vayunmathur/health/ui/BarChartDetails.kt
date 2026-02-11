package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.*
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
 * Configuration for different health metrics to make the UI reusable.
 * Converted to an enum class for easier serialization/navigation.
 */
enum class HealthMetricConfig(
    val title: String,
    val recordType: RecordType,
    val unit: String,
    val dailyGoal: Long
) {
    ENERGY("Energy Burned", RecordType.CaloriesTotal, "cal", 2470),
    ACTIVE_CALORIES("Active Calories", RecordType.CaloriesActive, "cal", 500),
    BASAL_METABOLIC_RATE("Basal Metabolic Rate", RecordType.CaloriesBasal, "cal", 1800),
    STEPS("Steps", RecordType.Steps, "steps", 10000),
    WHEELCHAIR_PUSHES("Wheelchair Pushes", RecordType.Wheelchair, "pushes", 3000),
    DISTANCE("Distance", RecordType.Distance, "km", 5),
    ELEVATION("Elevation Gained", RecordType.Elevation, "m", 50),
    FLOORS("Floors Climbed", RecordType.Floors, "floors", 10)
}

data class MetricDashboardData(
    val totalValue: Long = 0,
    val dailyAverage: Long = 0,
    val chartData: List<Pair<String, Long>> = emptyList(),
    val historyItems: List<HistoryItem> = emptyList(),
    val totalBarCount: Int = 0
)

data class HistoryItem(
    val label: String,
    val value: Long,
    val unit: String,
    val isGoalMet: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarChartDetails(
    backStack: NavBackStack<Route>,
    config: HealthMetricConfig
) {
    var selectedTab by remember { mutableIntStateOf(2) }
    val tabs = listOf("Day", "Week", "Month", "Year")

    // Switch to kotlinx-datetime
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
                Triple(start.atStartOfDayIn(tz), end.atStartOfDayIn(tz), HealthAPI.PeriodType.Weekly) // Or Monthly if you add it
            }
        }

        val rawSums = HealthAPI.getListOfSums(config.recordType, startTime, endTime, periodType)

        // Transform the raw sums into Chart and History data
        val mappedChart = rawSums.mapIndexed { index, value ->
            val label = when (selectedTab) {
                0 -> index.toString()
                1 -> startTime.plus(index.toLong(), DateTimeUnit.DAY, tz).toLocalDateTime(tz).dayOfWeek.name.take(3)
                else -> (index + 1).toString()
            }
            label to value.toLong()
        }

        val history = rawSums.mapIndexed { index, value ->
            val label = when (selectedTab) {
                0 -> "Hour $index"
                1 -> startTime.plus(index.toLong(), DateTimeUnit.DAY, tz).toLocalDateTime(tz).dayOfWeek.name.lowercase().capitalize()
                2 -> "Day ${index + 1}"
                else -> "Week ${index + 1}"
            }
            HistoryItem(label, value.toLong(), config.unit, value >= config.dailyGoal)
        }.filter { it.value > 0 }.reversed()

        val totalValue = rawSums.sum().toLong()
        val daysInRange = rawSums.size.coerceAtLeast(1)

        dataState = MetricDashboardData(
            totalValue = totalValue,
            dailyAverage = if (selectedTab == 0) totalValue else totalValue / daysInRange,
            chartData = mappedChart,
            historyItems = history,
            totalBarCount = rawSums.size
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
                    // Header Date Display
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
                        Text(
                            text = String.format("%,d", dataState.dailyAverage),
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Light
                        )
                        Text(
                            text = " ${config.unit}${if (selectedTab != 0) " per day (avg)" else ""}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                            color = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(32.dp))
                    GenericBarChart(
                        data = dataState.chartData,
                        totalBarCount = dataState.totalBarCount,
                        goalValue = config.dailyGoal,
                        barColor = MaterialTheme.colorScheme.primary,
                        goalColor = MaterialTheme.colorScheme.secondary
                    )
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
            drawLine(
                color = goalColor,
                start = Offset(0f, goalY),
                end = Offset(width, goalY),
                strokeWidth = 1.dp.toPx()
            )

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

        Text(
            text = String.format("%,d", maxChartValue.toLong()),
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        Text(
            text = String.format("%,d", goalValue),
            color = barColor,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(top = 40.dp)
        )
        Text(
            text = "0",
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
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
                if (item.isGoalMet) {
                    Icon(
                        painter = painterResource(R.drawable.baseline_check_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "${String.format("%,d", item.value)} ${item.unit}",
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}