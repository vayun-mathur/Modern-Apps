package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.pointerInput
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
import com.vayunmathur.health.ui.components.GroupedSection
import com.vayunmathur.health.ui.components.GroupedSectionDivider
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
                val accent = colorFor(config)
                SecondaryTabRow(
                    selectedTabIndex = selectedTab,
                    divider = {},
                    indicator = {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(selectedTab),
                            color = accent,
                        )
                    },
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            selectedContentColor = accent,
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
                    ChartHeader(
                        config = config,
                        avgString = run {
                            val formatVal = { v: Double ->
                                if (config.useDecimals) v.round(1).toString() else v.toLong().toStringCommas()
                            }
                            when {
                                config == HealthMetricConfig.HEART_RATE -> stringResource(
                                    R.string.dash_value_format,
                                    formatVal(dataState.primaryRange?.start ?: 0.0),
                                    formatVal(dataState.primaryRange?.endInclusive ?: 0.0)
                                )
                                dataState.secondaryAverage != null && config.isDualSeries -> stringResource(
                                    R.string.slash_value_format,
                                    formatVal(dataState.dailyAverage),
                                    formatVal(dataState.secondaryAverage!!)
                                )
                                else -> formatVal(dataState.dailyAverage)
                            }
                        },
                        unitLabel = if (selectedTab != 0 && !config.isLineChart) stringResource(
                            R.string.unit_per_day_avg_format, config.unit
                        ) else stringResource(R.string.unit_only_format, config.unit),
                    )
                }

                Spacer(Modifier.height(16.dp))

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
                        lineColor = colorFor(config),
                        secondaryLineColor = colorFor(config).copy(alpha = 0.6f),
                        goalColor = MaterialTheme.colorScheme.outline
                    )
                } else {
                    GenericBarChart(
                        data = dataState.chartData.map { it.first to (it.second ?: 0.0) },
                        totalBarCount = dataState.totalBarCount,
                        goalValue = chartGoalValue,
                        barColor = colorFor(config),
                        goalColor = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(), contentPadding = PaddingValues(
                    start = 0.dp,
                    end = padding.calculateRightPadding(LayoutDirection.Ltr),
                    bottom = 24.dp + padding.calculateBottomPadding()
                )
            ) {
                if (dataState.historyItems.isNotEmpty()) {
                    item {
                        GroupedSection {
                            dataState.historyItems.forEachIndexed { idx, item ->
                                if (idx > 0) GroupedSectionDivider()
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
    }
}

@Composable
private fun ChartHeader(
    config: HealthMetricConfig,
    avgString: String,
    unitLabel: String,
) {
    val accent = colorFor(config)
    val iconRes = iconResFor(config)
    Row(verticalAlignment = Alignment.Bottom) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp, bottom = 10.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = avgString,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            color = accent,
        )
        Text(
            text = unitLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
            color = LocalContentColor.current.copy(alpha = 0.6f)
        )
    }
}

private fun iconResFor(config: HealthMetricConfig): Int = when (config.recordType) {
    RecordType.Steps, RecordType.Distance, RecordType.Floors, RecordType.Elevation,
    RecordType.Wheelchair -> R.drawable.outline_directions_walk_24
    RecordType.CaloriesActive, RecordType.CaloriesTotal, RecordType.CaloriesBasal -> R.drawable.baseline_local_fire_department_24
    RecordType.HeartRate, RecordType.RestingHeartRate, RecordType.HeartRateVariabilityRmssd,
    RecordType.RespiratoryRate, RecordType.OxygenSaturation, RecordType.BloodPressure,
    RecordType.BloodGlucose, RecordType.Vo2Max, RecordType.SkinTemperature -> R.drawable.baseline_favorite_24
    RecordType.Weight, RecordType.Height, RecordType.BodyFat, RecordType.LeanBodyMass,
    RecordType.BoneMass, RecordType.BodyWaterMass -> R.drawable.body_24px
    RecordType.Sleep, RecordType.Mindfulness -> R.drawable.baseline_bedtime_24
    RecordType.Hydration -> R.drawable.baseline_bedtime_24
    RecordType.Nutrition -> R.drawable.baseline_local_fire_department_24
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
    val minSeriesValue = allValues.minOrNull() ?: 0.0
    val avgSeriesValue = if (allValues.isNotEmpty()) allValues.average() else 0.0
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)

    val chartHeight = 200.dp
    val xAxisHeight = 24.dp
    val sideLabelWidth = 44.dp

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

            // Subtle horizontal grid lines at 25/50/75/100%
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
                drawLine(
                    color = labelColor.copy(alpha = 0.10f),
                    start = Offset(0f, height * (1f - frac)),
                    end = Offset(width, height * (1f - frac)),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val drawSeries = { series: List<Pair<String, Double?>>, color: Color, isPrimary: Boolean ->
                // Filled area under the primary line
                if (isPrimary) {
                    val path = Path()
                    var started = false
                    var lastX = 0f
                    series.forEachIndexed { index, pair ->
                        val value = pair.second ?: return@forEachIndexed
                        val x = index * spacing
                        val y = height - (value.toFloat() / maxChartValue.toFloat() * height)
                            .coerceIn(0f, height)
                        if (!started) {
                            path.moveTo(x, height)
                            path.lineTo(x, y)
                            started = true
                        } else {
                            path.lineTo(x, y)
                        }
                        lastX = x
                    }
                    if (started) {
                        path.lineTo(lastX, height)
                        path.close()
                        drawPath(path, color = color.copy(alpha = 0.15f))
                    }
                }
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
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                        drawCircle(color = color, radius = 2.5.dp.toPx(), center = currentPoint)
                        lastValidPoint = currentPoint
                    }
                }
            }

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
                    ) else PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                )
            }

            drawGoal(goalValue, goalColor, false)
            secondaryGoal?.let { drawGoal(it, goalColor.copy(alpha = 0.5f), true) }

            drawSeries(data, lineColor, true)
            secondaryData?.let { drawSeries(it, secondaryLineColor, false) }
        }

        // Decimated X-axis labels
        val labelStep = decimationStep(data.size)
        data.forEachIndexed { index, pair ->
            if (pair.first.isNotEmpty() && index % labelStep == 0) {
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

        // Y-axis labels: max, avg, min, 0
        val format = { v: Double ->
            if (v >= 10000) resources.getString(
                R.string.k_format, (v / 1000).round(0).toString()
            ) else v.toLong().toStringCommas()
        }

        YAxisLabel(format(maxChartValue), labelColor, sideLabelWidth, 0f, density)
        if (allValues.isNotEmpty()) {
            val avgY = (1f - (avgSeriesValue.toFloat() / maxChartValue.toFloat())) * actualChartHeightPx
            YAxisLabel("avg ${format(avgSeriesValue)}", lineColor, sideLabelWidth, avgY, density)
            val minY = (1f - (minSeriesValue.toFloat() / maxChartValue.toFloat())) * actualChartHeightPx
            YAxisLabel("min ${format(minSeriesValue)}", labelColor, sideLabelWidth, minY, density)
        }
        YAxisLabel("0", labelColor, sideLabelWidth, actualChartHeightPx, density)
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
    val avgValueFound = if (data.isNotEmpty()) data.map { it.second }.average() else 0.0
    val maxChartValue = (maxValueFound.toFloat() * 1.2f).coerceAtLeast(goalValue.toFloat() * 1.2f)
        .coerceAtLeast(10f)
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)
    var selectedIndex by remember(data) { mutableIntStateOf(-1) }

    val chartHeight = 200.dp
    val xAxisHeight = 24.dp
    val sideLabelWidth = 44.dp
    val tooltipHeight = 20.dp

    val context = LocalContext.current
    val resources = context.resources
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight + tooltipHeight)
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val sideLabelWidthPx = with(density) { sideLabelWidth.toPx() }
        val chartWidthPx = fullWidthPx - sideLabelWidthPx

        val barWidth = with(density) { 6.dp.toPx() }
        val spacing =
            (chartWidthPx - (totalBarCount * barWidth)) / (totalBarCount + 1).coerceAtLeast(1)
        val format = { v: Double ->
            if (v >= 10000) resources.getString(
                R.string.k_format, (v / 1000).round(0).toString()
            ) else v.toLong().toStringCommas()
        }

        // Tooltip above selected bar
        if (selectedIndex in data.indices) {
            val pair = data[selectedIndex]
            val barCenterX = spacing + selectedIndex * (barWidth + spacing) + barWidth / 2
            Text(
                text = format(pair.second),
                color = barColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = with(density) { barCenterX.toDp() })
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(-(placeable.width / 2), 0)
                        }
                    }
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .align(Alignment.BottomStart)
                .padding(end = sideLabelWidth, bottom = xAxisHeight)
                .pointerInput(data, totalBarCount) {
                    detectTapGestures { offset ->
                        val tapX = offset.x
                        val matched = data.indices.firstOrNull { idx ->
                            val left = spacing + idx * (barWidth + spacing)
                            tapX >= left - spacing / 2 && tapX <= left + barWidth + spacing / 2
                        } ?: -1
                        selectedIndex = if (matched == selectedIndex) -1 else matched
                    }
                }
        ) {
            val height = size.height
            val width = size.width

            // Subtle horizontal grid lines at 25/50/75/100%
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
                drawLine(
                    color = labelColor.copy(alpha = 0.10f),
                    start = Offset(0f, height * (1f - frac)),
                    end = Offset(width, height * (1f - frac)),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val goalY = height - (goalValue.toFloat() / maxChartValue * height).coerceIn(0f, height)
            drawLine(
                color = goalColor,
                start = Offset(0f, goalY),
                end = Offset(width, goalY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )

            data.forEachIndexed { index, pair ->
                val barHeight =
                    (pair.second.toFloat() / maxChartValue * height).coerceIn(
                        if (pair.second > 0) 4f else 0f, height
                    )
                val x = spacing + index * (barWidth + spacing)
                val y = height - barHeight
                val drawColor = if (selectedIndex < 0 || selectedIndex == index) barColor
                else barColor.copy(alpha = 0.6f)
                drawRoundRect(
                    color = drawColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
        }

        // Decimated X-axis labels
        val labelStep = decimationStep(totalBarCount)
        data.forEachIndexed { index, pair ->
            if (pair.first.isNotEmpty() && index % labelStep == 0) {
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

        // Y-axis labels: max, avg, 0
        val actualChartHeightPx = with(density) { (chartHeight - xAxisHeight).toPx() }
        val topOffsetPx = with(density) { tooltipHeight.toPx() }
        YAxisLabel(format(maxChartValue.toDouble()), labelColor, sideLabelWidth, topOffsetPx, density)
        if (data.isNotEmpty()) {
            val avgY = topOffsetPx + (1f - (avgValueFound.toFloat() / maxChartValue)) * actualChartHeightPx
            YAxisLabel("avg ${format(avgValueFound)}", barColor, sideLabelWidth, avgY, density)
        }
        YAxisLabel("0", labelColor, sideLabelWidth, topOffsetPx + actualChartHeightPx, density)
    }
}

private fun decimationStep(count: Int): Int = when {
    count <= 8 -> 1
    count <= 16 -> 2
    count <= 24 -> 3
    else -> 5
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.YAxisLabel(
    text: String,
    color: Color,
    sideLabelWidth: Dp,
    yPx: Float,
    density: androidx.compose.ui.unit.Density,
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .width(sideLabelWidth)
            .offset(y = with(density) { yPx.toDp() })
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, -(placeable.height / 2))
                }
            }
            .padding(start = 4.dp)
    )
}
