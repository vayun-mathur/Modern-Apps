package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.util.displayString
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.round
import com.vayunmathur.library.util.toStringCommas
import com.vayunmathur.library.util.toStringDigits
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
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
    ENERGY(R.string.metric_energy_burned, RecordType.CaloriesTotal, "cal", 2470.0), ACTIVE_CALORIES(
        R.string.metric_active_calories,
        RecordType.CaloriesActive,
        "cal",
        500.0
    ),
    BASAL_METABOLIC_RATE(
        R.string.metric_basal_metabolic_rate, RecordType.CaloriesBasal, "cal", 1800.0
    ),
    STEPS(
        R.string.metric_steps,
        RecordType.Steps,
        "steps",
        10000.0
    ),
    WHEELCHAIR_PUSHES(
        R.string.metric_wheelchair_pushes,
        RecordType.Wheelchair,
        "pushes",
        3000.0
    ),
    DISTANCE(
        R.string.metric_distance,
        RecordType.Distance,
        "km",
        5.0,
        isLineChart = false,
        useDecimals = true
    ),
    ELEVATION(
        R.string.metric_elevation_gained,
        RecordType.Elevation,
        "m",
        50.0,
        isLineChart = false,
        useDecimals = true
    ),
    FLOORS(
        R.string.metric_floors_climbed,
        RecordType.Floors,
        "floors",
        10.0,
        isLineChart = false,
        useDecimals = true
    ),
    HYDRATION(
        R.string.metric_hydration,
        RecordType.Hydration,
        "L",
        2.5,
        isLineChart = false,
        useDecimals = true
    ),

    // Biological & Medical (Line Charts)
    BLOOD_PRESSURE(
        R.string.metric_blood_pressure,
        RecordType.BloodPressure,
        "mmHg",
        120.0,
        secondaryGoal = 80.0,
        isLineChart = true,
        isDualSeries = true
    ),
    GLUCOSE(
        R.string.metric_blood_glucose,
        RecordType.BloodGlucose,
        "mg/dL",
        100.0,
        isLineChart = true,
        useDecimals = true
    ),
    VO2_MAX(
        R.string.metric_vo2_max,
        RecordType.Vo2Max,
        "ml/kg/min",
        45.0,
        isLineChart = true,
        useDecimals = true
    ),
    SKIN_TEMP(
        R.string.metric_skin_temp_variation,
        RecordType.SkinTemperature,
        "°C",
        0.0,
        isLineChart = true,
        useDecimals = true
    ),
    BREATHING_RATE(
        R.string.metric_breathing_rate,
        RecordType.RespiratoryRate,
        "brpm",
        16.0,
        isLineChart = true,
        useDecimals = true
    ),
    RESTING_HEART_RATE(
        R.string.metric_resting_heart_rate,
        RecordType.RestingHeartRate,
        "bpm",
        60.0,
        isLineChart = true
    ),
    OXYGEN_SATURATION(
        R.string.metric_oxygen_saturation,
        RecordType.OxygenSaturation,
        "%",
        95.0,
        isLineChart = true,
        useDecimals = true
    ),
    HRV(
        R.string.metric_heart_rate_variability,
        RecordType.HeartRateVariabilityRmssd,
        "ms",
        50.0,
        isLineChart = true,
        useDecimals = true
    ),
    HEART_RATE(R.string.metric_heart_rate, RecordType.HeartRate, "bpm", 100.0, isLineChart = true),

    // Physical Measurements
    HEIGHT(
        R.string.metric_height,
        RecordType.Height,
        "cm",
        175.0,
        isLineChart = true,
        useDecimals = true
    ),
    WEIGHT(
        R.string.metric_weight,
        RecordType.Weight,
        "kg",
        75.0,
        isLineChart = true,
        useDecimals = true
    ),
    BODY_FAT(
        R.string.metric_body_fat,
        RecordType.BodyFat,
        "%",
        20.0,
        isLineChart = true,
        useDecimals = true
    ),
    LEAN_BODY_MASS(
        R.string.metric_lean_body_mass,
        RecordType.LeanBodyMass,
        "kg",
        60.0,
        isLineChart = true,
        useDecimals = true
    ),
    BONE_MASS(
        R.string.metric_bone_mass,
        RecordType.BoneMass,
        "kg",
        3.0,
        isLineChart = true,
        useDecimals = true
    ),
    BODY_WATER_MASS(
        R.string.metric_body_water_mass,
        RecordType.BodyWaterMass,
        "kg",
        45.0,
        isLineChart = true,
        useDecimals = true
    ),
    SLEEP(
        R.string.metric_sleep, RecordType.Sleep, "hr", 8.0, isLineChart = false, useDecimals = true
    )
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
    backStack: NavBackStack<Route>, viewModel: HealthViewModel, config: HealthMetricConfig
) {
    var selectedTab by remember { mutableIntStateOf(if (config == HealthMetricConfig.HEART_RATE) 0 else 1) }
    val tabs = listOf(
        stringResource(R.string.tab_day),
        stringResource(R.string.tab_week),
        stringResource(R.string.tab_month),
        stringResource(R.string.tab_year)
    )

    var anchorDate by remember { mutableStateOf(Clock.System.todayIn(TimeZone.currentSystemDefault())) }
    val dataState by viewModel.barChartData.collectAsState()

    LaunchedEffect(selectedTab, anchorDate, config) {
        viewModel.loadBarChartData(config, anchorDate, selectedTab)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(config.titleRes)) },
                navigationIcon = { IconNavigation(backStack) })
        }) { padding ->
        Column(
            modifier = Modifier
                .padding(top = padding.calculateTopPadding())
                .fillMaxSize()
        ) {
            if (config != HealthMetricConfig.HEART_RATE) {
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
                            })
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp)
            ) {
                val headerLabel = when (selectedTab) {
                    0 -> anchorDate.displayString()
                    1 -> {
                        val start = anchorDate.minus(
                            (anchorDate.dayOfWeek.ordinal + 1) % 7, DateTimeUnit.DAY
                        )
                        val end = start.plus(6, DateTimeUnit.DAY)
                        stringResource(
                            R.string.week_range, start.displayString(), end.displayString()
                        )
                    }

                    2 -> stringResource(
                        R.string.month_year_format,
                        anchorDate.month.name.lowercase().replaceFirstChar { it.uppercase() },
                        anchorDate.year
                    )

                    else -> anchorDate.year.toString()
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = {
                        anchorDate = when (selectedTab) {
                            0 -> anchorDate.minus(1, DateTimeUnit.DAY)
                            1 -> anchorDate.minus(1, DateTimeUnit.WEEK)
                            2 -> anchorDate.minus(1, DateTimeUnit.MONTH)
                            else -> anchorDate.minus(1, DateTimeUnit.YEAR)
                        }
                    }) {
                        Icon(
                            painterResource(R.drawable.baseline_arrow_back_24),
                            stringResource(R.string.nav_prev)
                        )
                    }

                    Text(
                        headerLabel,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(min = 140.dp),
                        textAlign = TextAlign.Center
                    )

                    val nextDate = when (selectedTab) {
                        0 -> anchorDate.plus(1, DateTimeUnit.DAY)
                        1 -> anchorDate.plus(1, DateTimeUnit.WEEK)
                        2 -> anchorDate.plus(1, DateTimeUnit.MONTH)
                        else -> anchorDate.plus(1, DateTimeUnit.YEAR)
                    }

                    IconButton(
                        onClick = {
                            anchorDate = when (selectedTab) {
                                0 -> anchorDate.plus(1, DateTimeUnit.DAY)
                                1 -> anchorDate.plus(1, DateTimeUnit.WEEK)
                                2 -> anchorDate.plus(1, DateTimeUnit.MONTH)
                                else -> anchorDate.plus(1, DateTimeUnit.YEAR)
                            }
                        },
                        enabled = nextDate <= Clock.System.todayIn(TimeZone.currentSystemDefault())
                    ) {
                        Icon(
                            painterResource(R.drawable.outline_arrow_forward_24),
                            stringResource(R.string.nav_next)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                val hasData = dataState.chartData.any { it.second != null }
                val chartGoalValue =
                    if (config.isLineChart) config.dailyGoal else when (selectedTab) {
                        0 -> config.dailyGoal / 24.0
                        3 -> config.dailyGoal * 30.0
                        else -> config.dailyGoal
                    }

                if (hasData) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        val formatVal = { v: Double ->
                            if (config.useDecimals) v.round(1).toString() else v.toLong()
                                .toStringCommas()
                        }
                        val avgString = if (config == HealthMetricConfig.HEART_RATE) {
                            stringResource(
                                R.string.dash_value_format,
                                formatVal(dataState.primaryRange?.start ?: 0.0),
                                formatVal(dataState.primaryRange?.endInclusive ?: 0.0)
                            )
                        } else if (dataState.secondaryAverage != null && config.isDualSeries) {
                            stringResource(
                                R.string.slash_value_format,
                                formatVal(dataState.dailyAverage),
                                formatVal(dataState.secondaryAverage!!)
                            )
                        } else {
                            formatVal(dataState.dailyAverage)
                        }

                        Text(
                            text = avgString,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Light
                        )
                        Text(
                            text = if (selectedTab != 0 && !config.isLineChart) stringResource(
                                R.string.unit_per_day_avg_format, config.unit
                            ) else stringResource(R.string.unit_only_format, config.unit),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                            color = LocalContentColor.current.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                if (!hasData) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp), contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.no_data_available),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else if (config.isLineChart) {
                    GenericLineChart(
                        data = dataState.chartData,
                        secondaryData = dataState.secondaryChartData,
                        goalValue = chartGoalValue,
                        secondaryGoal = config.secondaryGoal?.let { it * (chartGoalValue / config.dailyGoal) },
                        lineColor = MaterialTheme.colorScheme.primary,
                        secondaryLineColor = MaterialTheme.colorScheme.tertiary,
                        goalColor = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    GenericBarChart(
                        data = dataState.chartData.map { it.first to (it.second ?: 0.0) },
                        totalBarCount = dataState.totalBarCount,
                        goalValue = chartGoalValue,
                        barColor = MaterialTheme.colorScheme.primary,
                        goalColor = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(), contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp + padding.calculateRightPadding(LayoutDirection.Ltr),
                    bottom = 24.dp + padding.calculateBottomPadding()
                )
            ) {
                itemsIndexed(dataState.historyItems) { idx, item ->
                    Card(
                        Modifier.padding(vertical = 2.dp),
                        shape = verticalSegmentedCardShape(idx, dataState.historyItems.size)
                    ) {
                        ListItem({
                            Text(item.label, style = MaterialTheme.typography.bodyLarge)
                        }, trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.isGoalMet) {
                                    IconCheck()
                                    Spacer(Modifier.width(4.dp))
                                }
                                val format = { v: Double ->
                                    if (item.useDecimals) v.toStringDigits(1) else v.toLong()
                                        .toStringCommas()
                                }
                                val valueString = if (item.secondaryValue != null) stringResource(
                                    R.string.slash_value_format,
                                    format(item.value),
                                    format(item.secondaryValue)
                                ) else format(item.value)
                                Text(
                                    stringResource(
                                        R.string.value_unit_space_format, valueString, item.unit
                                    ), style = MaterialTheme.typography.bodyLarge
                                )
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

    val allValues =
        (data.mapNotNull { it.second } + (secondaryData?.mapNotNull { it.second } ?: emptyList()))
    val maxChartValue =
        (allValues.maxOrNull() ?: goalValue).coerceAtLeast(goalValue * 1.2).coerceAtLeast(10.0)
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)

    val chartHeight = 180.dp
    val xAxisHeight = 24.dp
    val sideLabelWidth = 40.dp

    val context = LocalContext.current
    val resources = context.resources
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val sideLabelWidthPx = with(density) { sideLabelWidth.toPx() }
        val chartWidthPx = fullWidthPx - sideLabelWidthPx
        val actualChartHeightPx = with(density) { (chartHeight - xAxisHeight).toPx() }

        val spacing = chartWidthPx / (data.size - 1).coerceAtLeast(1)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = sideLabelWidth, bottom = xAxisHeight)
        ) {
            val height = size.height
            val width = size.width

            // Axis lines
            drawLine(
                color = labelColor.copy(alpha = 0.15f),
                start = Offset(0f, height),
                end = Offset(width, height),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = labelColor.copy(alpha = 0.15f),
                start = Offset(width, 0f),
                end = Offset(width, height),
                strokeWidth = 1.dp.toPx()
            )

            val drawSeries = { series: List<Pair<String, Double?>>, color: Color ->
                var lastValidPoint: Offset? = null
                series.forEachIndexed { index, pair ->
                    val value = pair.second
                    val x = index * spacing
                    if (value != null) {
                        val y =
                            height - (value.toFloat() / maxChartValue.toFloat() * height).coerceIn(
                                0f, height
                            )
                        val currentPoint = Offset(x, y)
                        if (lastValidPoint != null) {
                            drawLine(
                                color = color,
                                start = lastValidPoint,
                                end = currentPoint,
                                strokeWidth = 3.dp.toPx()
                            )
                        }
                        drawCircle(color = color, radius = 3.dp.toPx(), center = currentPoint)
                        lastValidPoint = currentPoint
                    }
                }
            }

            // Goal lines
            val drawGoal = { goal: Double, color: Color, isSecondary: Boolean ->
                val gy = height - (goal.toFloat() / maxChartValue.toFloat() * height).coerceIn(
                    0f, height
                )
                drawLine(
                    color = color,
                    start = Offset(0f, gy),
                    end = Offset(width, gy),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (isSecondary) PathEffect.dashPathEffect(
                        floatArrayOf(10f, 10f), 0f
                    ) else null
                )
            }

            drawGoal(goalValue, goalColor, false)
            secondaryGoal?.let { drawGoal(it, goalColor.copy(alpha = 0.5f), true) }

            drawSeries(data, lineColor)
            secondaryData?.let { drawSeries(it, secondaryLineColor) }
        }

        // X-axis labels
        data.forEachIndexed { index, pair ->
            if (pair.first.isNotEmpty()) {
                val x = index * spacing
                Text(
                    text = pair.first,
                    color = labelColor,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = with(density) { x.toDp() })
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                placeable.placeRelative(-(placeable.width / 2), 0)
                            }
                        })
            }
        }

        // Y-axis labels
        val format = { v: Double ->
            if (v >= 10000) resources.getString(
                R.string.k_format, (v / 1000).round(0).toString()
            ) else v.toLong().toStringCommas()
        }

        Text(
            text = format(maxChartValue),
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(sideLabelWidth)
                .padding(start = 4.dp)
        )

        val goalY = (1f - (goalValue.toFloat() / maxChartValue.toFloat())) * actualChartHeightPx
        Text(
            text = format(goalValue),
            color = goalColor,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(sideLabelWidth)
                .offset(y = with(density) { goalY.toDp() })
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, -(placeable.height / 2))
                    }
                }
                .padding(start = 4.dp))

        Text(
            text = "0",
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(sideLabelWidth)
                .offset(y = with(density) { actualChartHeightPx.toDp() })
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, -(placeable.height / 2))
                    }
                }
                .padding(start = 4.dp))
    }
}

@Composable
fun GenericBarChart(
    data: List<Pair<String, Double>>,
    totalBarCount: Int,
    goalValue: Double,
    barColor: Color,
    goalColor: Color
) {
    val maxValueFound = data.maxOfOrNull { it.second } ?: 0.0
    val maxChartValue = (maxValueFound.toFloat() * 1.2f).coerceAtLeast(goalValue.toFloat() * 1.2f)
        .coerceAtLeast(10f)
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)

    val chartHeight = 180.dp
    val xAxisHeight = 24.dp
    val sideLabelWidth = 40.dp

    val context = LocalContext.current
    val resources = context.resources
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val sideLabelWidthPx = with(density) { sideLabelWidth.toPx() }
        val chartWidthPx = fullWidthPx - sideLabelWidthPx
        val actualChartHeightPx = with(density) { (chartHeight - xAxisHeight).toPx() }

        val barWidth = with(density) { 6.dp.toPx() }
        val spacing =
            (chartWidthPx - (totalBarCount * barWidth)) / (totalBarCount + 1).coerceAtLeast(1)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = sideLabelWidth, bottom = xAxisHeight)
        ) {
            val height = size.height
            val width = size.width

            // Axis lines
            drawLine(
                color = labelColor.copy(alpha = 0.15f),
                start = Offset(0f, height),
                end = Offset(width, height),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = labelColor.copy(alpha = 0.15f),
                start = Offset(width, 0f),
                end = Offset(width, height),
                strokeWidth = 1.dp.toPx()
            )

            val goalY = height - (goalValue.toFloat() / maxChartValue * height).coerceIn(0f, height)
            drawLine(
                color = goalColor,
                start = Offset(0f, goalY),
                end = Offset(width, goalY),
                strokeWidth = 1.dp.toPx()
            )

            data.forEachIndexed { index, pair ->
                val barHeight =
                    (pair.second.toFloat() / maxChartValue * height).coerceIn(4f, height)
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

        // X-axis labels
        data.forEachIndexed { index, pair ->
            if (pair.first.isNotEmpty()) {
                val x = spacing + index * (barWidth + spacing) + barWidth / 2
                Text(
                    text = pair.first,
                    color = labelColor,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = with(density) { x.toDp() })
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) {
                                placeable.placeRelative(-(placeable.width / 2), 0)
                            }
                        })
            }
        }

        // Y-axis labels
        val format = { v: Double ->
            if (v >= 10000) resources.getString(
                R.string.k_format, (v / 1000).round(0).toString()
            ) else v.toLong().toStringCommas()
        }

        Text(
            text = format(maxChartValue.toDouble()),
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(sideLabelWidth)
                .padding(start = 4.dp)
        )

        val goalY = (1f - (goalValue.toFloat() / maxChartValue)) * actualChartHeightPx
        Text(
            text = format(goalValue),
            color = goalColor,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(sideLabelWidth)
                .offset(y = with(density) { goalY.toDp() })
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, -(placeable.height / 2))
                    }
                }
                .padding(start = 4.dp))

        Text(
            text = "0",
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(sideLabelWidth)
                .offset(y = with(density) { actualChartHeightPx.toDp() })
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, -(placeable.height / 2))
                    }
                }
                .padding(start = 4.dp))
    }
}
