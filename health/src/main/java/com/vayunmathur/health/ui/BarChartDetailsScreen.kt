package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.platform.MetricDetailsActions
import com.vayunmathur.health.platform.MetricDetailsUiState
import com.vayunmathur.health.platform.displayString
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SecondaryTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.TabRowDefaults
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedMonthNames
import com.vayunmathur.library.util.round
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.util.toStringCommas
import com.vayunmathur.library.util.toStringDigits
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/**
 * The per-metric chart screen, with no dependency on the ViewModel or the back stack so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`, which is where the store
 * listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarChartDetailsScreen(
    state: MetricDetailsUiState,
    actions: MetricDetailsActions,
    /**
     * Seeds for the screen's own UI-only state (which period tab is selected, which period
     * is being looked at). The app always takes the defaults; previews set them so a given
     * view can be captured without driving the UI to get there.
     */
    initialTab: Int = if (state.config == HealthMetricConfig.HEART_RATE) 0 else 1,
    initialAnchorDate: LocalDate = state.today,
) {
    val config = state.config
    val dataState = state.data

    var selectedTab by remember { mutableIntStateOf(initialTab) }
    val tabs = listOf(
        stringResource(R.string.tab_day),
        stringResource(R.string.tab_week),
        stringResource(R.string.tab_month),
        stringResource(R.string.tab_year)
    )

    var anchorDate by remember { mutableStateOf(initialAnchorDate) }

    val dateUnit = when (selectedTab) {
        0 -> DateTimeUnit.DAY
        1 -> DateTimeUnit.WEEK
        2 -> DateTimeUnit.MONTH
        else -> DateTimeUnit.YEAR
    }

    LaunchedEffect(selectedTab, anchorDate, config) {
        actions.loadBarChartData(config, anchorDate, selectedTab)
    }

    AppScaffold(
        title = {
            Text(
                stringResource(config.titleRes),
                modifier = Modifier.sharedText("health-metric-label-${config.name}"),
            )
        },
        onNavigateBack = { actions.navigateUp() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
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
                            R.string.date_range, start.displayString(), end.displayString()
                        )
                    }

                    2 -> stringResource(
                        R.string.month_year_format,
                        localizedMonthNames(DateNameStyle.FULL)[anchorDate.month.number - 1],
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
                        anchorDate = anchorDate.minus(1, dateUnit)
                    }) {
                        IconBack()
                    }

                    Text(
                        headerLabel,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(min = 140.dp),
                        textAlign = TextAlign.Center
                    )

                    val nextDate = anchorDate.plus(1, dateUnit)

                    IconButton(
                        onClick = { anchorDate = nextDate },
                        enabled = nextDate <= state.today
                    ) {
                        IconArrowForward()
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
                                    formatVal(dataState.secondaryAverage)
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
                    EmptyState(
                        title = stringResource(R.string.no_data_available),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
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
                        DashboardSection {
                            dataState.historyItems.forEachIndexed { idx, item ->
                                if (idx > 0) DashboardSectionDivider()
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
