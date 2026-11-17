package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.FloatingActionButtonMenu
import com.vayunmathur.library.ui.FloatingActionButtonMenuItem
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.components.MetricRow
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.MainPageMetrics
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconBodySystem
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.util.round

private val bodyMetricConfigs = listOf(
    HealthMetricConfig.WEIGHT,
    HealthMetricConfig.HEIGHT,
    HealthMetricConfig.BODY_FAT,
    HealthMetricConfig.LEAN_BODY_MASS,
    HealthMetricConfig.BONE_MASS,
    HealthMetricConfig.BODY_WATER_MASS,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BodyPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val metrics: MainPageMetrics by viewModel.mainPageMetrics.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadMainPageMetrics()
    }

    var fabExpanded by remember { mutableStateOf(false) }
    var dialogConfig by remember { mutableStateOf<HealthMetricConfig?>(null) }

    dialogConfig?.let { config ->
        LogBodyMetricDialog(
            viewModel = viewModel,
            config = config,
            onDismiss = { dialogConfig = null },
        )
    }

    LazyListScaffold(
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fabExpanded,
                button = {
                    ToggleFloatingActionButton(fabExpanded, { fabExpanded = it }) {
                        val tint = MaterialTheme.colorScheme.onPrimaryContainer
                        if (!fabExpanded) IconAdd(tint = tint) else IconClose(tint = tint)
                    }
                },
            ) {
                bodyMetricConfigs.forEach { config ->
                    FloatingActionButtonMenuItem(
                        onClick = { fabExpanded = false; dialogConfig = config },
                        text = { Text(stringResource(config.titleRes)) },
                        icon = {
                            IconBodySystem(
                                tint = colorFor(config.recordType),
                            )
                        },
                    )
                }
            }
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
            // Body composition
            item {
                DashboardSection(
                    title = stringResource(R.string.section_body_composition),
                    accentColor = HealthColors.Body,
                ) {
                    MetricRow(
                        label = stringResource(R.string.metric_weight),
                        value = metrics.weight?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_kg),
                        leadingIcon = { m, c -> IconBodySystem(m, c) },
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.Weight),
                        labelModifier = Modifier.sharedText("health-metric-label-WEIGHT"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.WEIGHT)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_height),
                        value = metrics.height?.let { (it * 100).round(1).toString() } ?: "--",
                        unit = stringResource(R.string.unit_cm),
                        leadingIcon = { m, c -> IconBodySystem(m, c) },
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.Height),
                        labelModifier = Modifier.sharedText("health-metric-label-HEIGHT"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.HEIGHT)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_body_fat),
                        value = metrics.bodyFat?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_percent),
                        leadingIcon = { m, c -> IconBodySystem(m, c) },
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.BodyFat),
                        labelModifier = Modifier.sharedText("health-metric-label-BODY_FAT"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.BODY_FAT)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_lean_body_mass),
                        value = metrics.leanBodyMass?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_kg),
                        leadingIcon = { m, c -> IconBodySystem(m, c) },
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.LeanBodyMass),
                        labelModifier = Modifier.sharedText("health-metric-label-LEAN_BODY_MASS"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.LEAN_BODY_MASS)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_bone_mass),
                        value = metrics.boneMass?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_kg),
                        leadingIcon = { m, c -> IconBodySystem(m, c) },
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.BoneMass),
                        labelModifier = Modifier.sharedText("health-metric-label-BONE_MASS"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.BONE_MASS)) },
                    )
                    DashboardSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.metric_body_water_mass),
                        value = metrics.bodyWaterMass?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_kg),
                        leadingIcon = { m, c -> IconBodySystem(m, c) },
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.BodyWaterMass),
                        labelModifier = Modifier.sharedText("health-metric-label-BODY_WATER_MASS"),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.BODY_WATER_MASS)) },
                    )
                }
            }
        }
}
