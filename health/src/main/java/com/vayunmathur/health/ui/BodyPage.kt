package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.components.GroupedSection
import com.vayunmathur.health.ui.components.GroupedSectionDivider
import com.vayunmathur.health.ui.components.MetricRow
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.MainPageMetrics
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.util.NavBackStack
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.nav_body)) })
        },
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
                            Icon(
                                painter = painterResource(R.drawable.body_24px),
                                contentDescription = null,
                                tint = colorFor(config.recordType),
                            )
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Body composition
            item {
                GroupedSection(
                    title = stringResource(R.string.section_body_composition),
                    accentColor = HealthColors.Body,
                ) {
                    MetricRow(
                        label = stringResource(R.string.label_weight),
                        value = metrics.weight?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_kg),
                        leadingIconRes = R.drawable.body_24px,
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.Weight),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.WEIGHT)) },
                    )
                    GroupedSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_height),
                        value = metrics.height?.let { (it * 100).round(1).toString() } ?: "--",
                        unit = stringResource(R.string.unit_cm),
                        leadingIconRes = R.drawable.body_24px,
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.Height),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.HEIGHT)) },
                    )
                    GroupedSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_body_fat),
                        value = metrics.bodyFat?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_percent),
                        leadingIconRes = R.drawable.body_24px,
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.BodyFat),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.BODY_FAT)) },
                    )
                    GroupedSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_lean_body_mass),
                        value = metrics.leanBodyMass?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_kg),
                        leadingIconRes = R.drawable.body_24px,
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.LeanBodyMass),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.LEAN_BODY_MASS)) },
                    )
                    GroupedSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_bone_mass),
                        value = metrics.boneMass?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_kg),
                        leadingIconRes = R.drawable.body_24px,
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.BoneMass),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.BONE_MASS)) },
                    )
                    GroupedSectionDivider()
                    MetricRow(
                        label = stringResource(R.string.label_body_water_mass),
                        value = metrics.bodyWaterMass?.round(1)?.toString() ?: "--",
                        unit = stringResource(R.string.unit_kg),
                        leadingIconRes = R.drawable.body_24px,
                        leadingTint = colorFor(com.vayunmathur.health.data.RecordType.BodyWaterMass),
                        onClick = { backStack.add(Route.BarChartDetails(HealthMetricConfig.BODY_WATER_MASS)) },
                    )
                }
            }
        }
    }
}
