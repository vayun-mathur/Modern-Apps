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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*
import com.vayunmathur.health.R
import java.time.Duration
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Configuration for different health metrics to make the UI reusable.
 * Converted to an enum class for easier serialization/navigation.
 */
enum class HealthMetricConfig(
    val title: String,
    val metric: AggregateMetric<*>,
    val unit: String,
    val dailyGoal: Long,
    val getValue: (AggregationResult) -> Long
) {
    ENERGY(
        title = "Energy Burned",
        metric = TotalCaloriesBurnedRecord.ENERGY_TOTAL,
        unit = "cal",
        dailyGoal = 2470,
        getValue = { it[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong() ?: 0L }
    ),

    ACTIVE_CALORIES(
        title = "Active Calories",
        metric = ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
        unit = "cal",
        dailyGoal = 500,
        getValue = { it[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toLong() ?: 0L }
    ),

    BASAL_METABOLIC_RATE(
        title = "Basal Metabolic Rate",
        metric = BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL,
        unit = "cal",
        dailyGoal = 1800,
        getValue = { it[BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL]?.inKilocalories?.toLong() ?: 0L }
    ),

    STEPS(
        title = "Steps",
        metric = StepsRecord.COUNT_TOTAL,
        unit = "steps",
        dailyGoal = 10000,
        getValue = { it[StepsRecord.COUNT_TOTAL] ?: 0L }
    ),

    WHEELCHAIR_PUSHES(
        title = "Wheelchair Pushes",
        metric = WheelchairPushesRecord.COUNT_TOTAL,
        unit = "pushes",
        dailyGoal = 3000,
        getValue = { it[WheelchairPushesRecord.COUNT_TOTAL] ?: 0L }
    ),

    DISTANCE(
        title = "Distance",
        metric = DistanceRecord.DISTANCE_TOTAL,
        unit = "km",
        dailyGoal = 5,
        getValue = { it[DistanceRecord.DISTANCE_TOTAL]?.inKilometers?.toLong() ?: 0L }
    ),

    ELEVATION(
        title = "Elevation Gained",
        metric = ElevationGainedRecord.ELEVATION_GAINED_TOTAL,
        unit = "m",
        dailyGoal = 50,
        getValue = { it[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters?.toLong() ?: 0L }
    ),

    FLOORS(
        title = "Floors Climbed",
        metric = FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL,
        unit = "floors",
        dailyGoal = 10,
        getValue = { it[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.toInt()?.toLong() ?: 0L }
    )
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
    var selectedTab by remember { mutableStateOf(2) } // 0:Day, 1:Week, 2:Month, 3:Year
    val tabs = listOf("Day", "Week", "Month", "Year")
    var anchorDate by remember { mutableStateOf(LocalDate.now()) }
    var dataState by remember { mutableStateOf(MetricDashboardData()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedTab, anchorDate, config) {
        scope.launch {
            try {
                val metrics = setOf(config.metric)
                val filter = when (selectedTab) {
                    0 -> HealthAPI.timeRangeToday(anchorDate)
                    1 -> HealthAPI.timeRangeThisWeek(anchorDate)
                    2 -> HealthAPI.timeRangeThisMonth(anchorDate)
                    else -> HealthAPI.timeRangeThisYear(anchorDate)
                }

                val mappedChart: List<Pair<String, Long>>
                val history: List<HistoryItem>
                val totalValue: Long
                val barCount: Int

                if (selectedTab == 0) {
                    barCount = 24
                    val hourlyData = HealthAPI.aggregateByDuration(filter, Duration.ofHours(1), metrics)
                    totalValue = hourlyData.sumOf { config.getValue(it.result) }

                    mappedChart = hourlyData.map { group ->
                        val value = config.getValue(group.result)
                        val label = group.startTime.atZone(ZoneId.systemDefault()).hour.toString()
                        label to value
                    }
                    history = hourlyData.map { group ->
                        val value = config.getValue(group.result)
                        val hour = group.startTime.atZone(ZoneId.systemDefault()).hour
                        HistoryItem("Hour $hour", value, config.unit, false)
                    }.filter { it.value > 0 }.reversed()
                } else {
                    val period = if (selectedTab == 3) Period.ofMonths(1) else Period.ofDays(1)
                    barCount = when (selectedTab) {
                        1 -> 7
                        2 -> anchorDate.lengthOfMonth()
                        else -> 12
                    }

                    val grouped = HealthAPI.aggregateByPeriod(filter, period, metrics)
                    totalValue = grouped.sumOf { config.getValue(it.result) }

                    mappedChart = grouped.map { group ->
                        val totalMonthValue = config.getValue(group.result)

                        val displayValue = if (selectedTab == 3) {
                            val daysInMonth = ChronoUnit.DAYS.between(group.startTime, group.endTime).coerceAtLeast(1)
                            totalMonthValue / daysInMonth
                        } else totalMonthValue

                        val label = when (selectedTab) {
                            3 -> group.startTime.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                            else -> group.startTime.dayOfMonth.toString()
                        }
                        label to displayValue
                    }

                    history = grouped.map { group ->
                        val totalMonthValue = config.getValue(group.result)

                        val displayValue = if (selectedTab == 3) {
                            val daysInMonth = ChronoUnit.DAYS.between(group.startTime, group.endTime).coerceAtLeast(1)
                            totalMonthValue / daysInMonth
                        } else totalMonthValue

                        val label = when (selectedTab) {
                            1 -> group.startTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                            2 -> group.startTime.format(DateTimeFormatter.ofPattern("MMM d"))
                            else -> group.startTime.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                        }
                        HistoryItem(label, displayValue, config.unit, displayValue >= config.dailyGoal)
                    }.filter { it.value > 0 }.reversed()
                }

                val daysInRange = when (selectedTab) {
                    0 -> 1L
                    1 -> 7L
                    2 -> anchorDate.lengthOfMonth().toLong()
                    else -> if (anchorDate.isLeapYear) 366L else 365L
                }
                val dailyAvg = totalValue / daysInRange

                dataState = MetricDashboardData(
                    totalValue = totalValue,
                    dailyAverage = dailyAvg,
                    chartData = mappedChart,
                    historyItems = history,
                    totalBarCount = barCount
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
            ) {
                item {
                    val headerLabel = when (selectedTab) {
                        0 -> anchorDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                        1 -> "Week of ${anchorDate.with(java.time.DayOfWeek.MONDAY).format(DateTimeFormatter.ofPattern("MMM d"))}"
                        2 -> anchorDate.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                        else -> anchorDate.year.toString()
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = {
                            anchorDate = when (selectedTab) {
                                0 -> anchorDate.minusDays(1)
                                1 -> anchorDate.minusWeeks(1)
                                2 -> anchorDate.minusMonths(1)
                                else -> anchorDate.minusYears(1)
                            }
                        }) {
                            Icon(painterResource(R.drawable.baseline_arrow_back_24), contentDescription = "Prev")
                        }

                        Text(
                            text = headerLabel,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.widthIn(min = 140.dp),
                            textAlign = TextAlign.Center
                        )

                        IconButton(onClick = {
                            anchorDate = when (selectedTab) {
                                0 -> anchorDate.plusDays(1)
                                1 -> anchorDate.plusWeeks(1)
                                2 -> anchorDate.plusMonths(1)
                                else -> anchorDate.plusYears(1)
                            }
                        }) {
                            Icon(painterResource(R.drawable.outline_arrow_forward_24), contentDescription = "Next")
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
                            text = " ${config.unit} per day (avg)",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                            color = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        text = "Total ${config.title.lowercase()}: ${String.format("%,d", dataState.totalValue)} ${config.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    Spacer(Modifier.height(32.dp))
                    val colorScheme = MaterialTheme.colorScheme
                    GenericBarChart(
                        data = dataState.chartData,
                        totalBarCount = dataState.totalBarCount,
                        goalValue = config.dailyGoal,
                        barColor = colorScheme.primary,
                        goalColor = colorScheme.secondary
                    )
                }

                item {
                    Spacer(Modifier.height(32.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("History", fontWeight = FontWeight.Bold)
                        Text(
                            if (selectedTab == 0) config.unit.replaceFirstChar { it.uppercase() } else "Daily average",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
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