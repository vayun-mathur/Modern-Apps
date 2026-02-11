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
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
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

data class EnergyDashboardData(
    val totalCalories: Long = 0,
    val dailyAverage: Long = 0,
    val goalValue: Long = 2470,
    val chartData: List<Pair<String, Long>> = emptyList(),
    val historyItems: List<HistoryItem> = emptyList(),
    val totalBarCount: Int = 0
)

data class HistoryItem(
    val label: String,
    val value: Long,
    val isGoalMet: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarChartDetails(backStack: NavBackStack<Route>) {
    var selectedTab by remember { mutableStateOf(2) } // 0:Day, 1:Week, 2:Month, 3:Year
    val tabs = listOf("Day", "Week", "Month", "Year")
    var anchorDate by remember { mutableStateOf(LocalDate.now()) }
    var dataState by remember { mutableStateOf(EnergyDashboardData()) }
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    LaunchedEffect(selectedTab, anchorDate) {
        scope.launch {
            try {
                val (filter, period, barCount) = when (selectedTab) {
                    0 -> Triple(HealthAPI.timeRangeToday(anchorDate), Period.ofDays(1), 24)
                    1 -> Triple(HealthAPI.timeRangeThisWeek(anchorDate), Period.ofDays(1), 7)
                    2 -> Triple(HealthAPI.timeRangeThisMonth(anchorDate), Period.ofDays(1), anchorDate.lengthOfMonth())
                    else -> Triple(HealthAPI.timeRangeThisYear(anchorDate), Period.ofMonths(1), 12)
                }

                val metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL)

                // For Day view (selectedTab == 0), use Duration-based aggregation for hourly data
                val mappedChart: List<Pair<String, Long>>
                val history: List<HistoryItem>

                if (selectedTab == 0) {
                    val hourlyData = HealthAPI.aggregateByDuration(filter, Duration.ofHours(1), metrics)
                    mappedChart = hourlyData.map { group ->
                        val calories = group.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong() ?: 0L
                        val label = group.startTime.atZone(java.time.ZoneId.systemDefault()).hour.toString()
                        label to calories
                    }
                    history = hourlyData.map { group ->
                        val calories = group.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong() ?: 0L
                        val hour = group.startTime.atZone(java.time.ZoneId.systemDefault()).hour
                        HistoryItem("Hour $hour", calories, calories >= 100) // Lower threshold for hourly goal check
                    }.reversed()
                } else {
                    val grouped = HealthAPI.aggregateByPeriod(filter, period, metrics)
                    mappedChart = grouped.map { group ->
                        val calories = group.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong() ?: 0L
                        val label = when (selectedTab) {
                            3 -> group.startTime.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                            else -> group.startTime.dayOfMonth.toString()
                        }
                        label to calories
                    }
                    history = grouped.map { group ->
                        val calories = group.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toLong() ?: 0L
                        val label = when (selectedTab) {
                            1 -> group.startTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                            2 -> group.startTime.format(DateTimeFormatter.ofPattern("MMM d"))
                            else -> group.startTime.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                        }
                        HistoryItem(label, calories, calories >= 2470)
                    }.reversed()
                }

                val total = mappedChart.sumOf { it.second }
                val avg = if (mappedChart.isNotEmpty()) total / mappedChart.size else 0

                dataState = EnergyDashboardData(
                    totalCalories = total,
                    dailyAverage = avg,
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
                title = { Text("Energy burned", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconNavigation(backStack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    titleContentColor = colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(colorScheme.background)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = colorScheme.background,
                contentColor = colorScheme.primary,
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                color = if (selectedTab == index) colorScheme.onBackground else colorScheme.onBackground.copy(alpha = 0.6f),
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
                    val headerText = when (selectedTab) {
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
                            Icon(painterResource(R.drawable.baseline_arrow_back_24), contentDescription = "Prev", tint = colorScheme.onBackground)
                        }

                        Text(
                            text = headerText,
                            color = colorScheme.onBackground,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
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
                            Icon(painterResource(R.drawable.outline_arrow_forward_24), contentDescription = "Next", tint = colorScheme.onBackground)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format("%,d", dataState.dailyAverage),
                            color = colorScheme.onBackground,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Light
                        )
                        Text(
                            text = " cal per day (avg)",
                            color = colorScheme.onBackground.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                    }
                    Text(
                        text = "Total burned: ${String.format("%,d", dataState.totalCalories)} calories",
                        color = colorScheme.onBackground.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                item {
                    Spacer(Modifier.height(32.dp))
                    EnergyBarChart(
                        data = dataState.chartData,
                        totalBarCount = dataState.totalBarCount,
                        goalValue = dataState.goalValue,
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
                        Text("History", color = colorScheme.onBackground, fontWeight = FontWeight.Bold)
                        Text(if (selectedTab == 0) "Calories" else "Daily average", color = colorScheme.onBackground.copy(alpha = 0.6f), fontSize = 14.sp)
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
fun EnergyBarChart(
    data: List<Pair<String, Long>>,
    totalBarCount: Int,
    goalValue: Long,
    barColor: Color,
    goalColor: Color
) {
    val maxChartValue = 5000f
    val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)

    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val barWidth = 6.dp.toPx()
            val spacing = (width - (totalBarCount * barWidth)) / (totalBarCount + 1).coerceAtLeast(1)

            val goalY = height - (goalValue / maxChartValue * height)
            drawLine(
                color = goalColor,
                start = Offset(0f, goalY),
                end = Offset(width, goalY),
                strokeWidth = 1.dp.toPx()
            )

            data.forEachIndexed { index, pair ->
                val barHeight = (pair.second / maxChartValue * height).coerceIn(4f, height)
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

        Text("5,000", color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
        Text(goalValue.toString(), color = barColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(top = 40.dp))
        Text("0", color = labelColor, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
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
            Text(item.label, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isGoalMet) {
                    Icon(
                        painterResource(R.drawable.baseline_check_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "${String.format("%,d", item.value)} cal",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}