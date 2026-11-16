package com.vayunmathur.screentime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.screentime.R
import com.vayunmathur.screentime.platform.AppDetailsUiState
import com.vayunmathur.screentime.platform.UsagePeriod
import java.time.LocalDate

/** Per-app drill-down: week/day usage scoped to one app, plus its timer and pause. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailsScreen(
    state: AppDetailsUiState,
    onBack: () -> Unit,
    onSetPeriod: (UsagePeriod) -> Unit,
    onStep: (Boolean) -> Unit,
    onTimerChange: (Int?) -> Unit,
    onPausedChange: (Boolean) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val scrollBehavior = appBarScrollBehavior()
    val title = state.label ?: stringResource(R.string.details_title)

    LazyListScaffold(
        horizontalPadding = 0.dp,
        scrollBehavior = scrollBehavior,
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { IconBack() }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        state.packageName?.let {
                            AppIconImage(it, Modifier.size(28.dp).clip(CircleShape))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(title)
                    }
                },
            )
        },
    ) {
        item { DetailPeriodToggle(state.period, onSetPeriod) }

        if (state.hasUsageAccess) {
            item { DetailUsageCard(state, onStep) }
        }

        item {
            SettingsSection(title = stringResource(R.string.details_usage)) {
                SettingsRow(
                    title = stringResource(R.string.details_timer),
                    supportingText = state.timerMinutes?.let { formatMinutes(it.toLong()) }
                        ?: stringResource(R.string.timer_none),
                    onClick = { picking = true },
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.details_pause_title)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.details_pause_app),
                    supportingText = stringResource(R.string.details_pause_app_hint),
                    checked = state.appPaused,
                    onCheckedChange = onPausedChange,
                )
            }
        }

        if (state.packageName == null) {
            item { SettingsSection { SettingsRow(title = stringResource(R.string.details_no_app)) } }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(title) },
            text = {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.timer_none),
                        onClick = { onTimerChange(null); picking = false },
                    )
                    for (minutes in DETAILS_CHOICES) {
                        SettingsRow(
                            title = formatMinutes(minutes.toLong()),
                            onClick = { onTimerChange(minutes); picking = false },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPeriodToggle(period: UsagePeriod, onSetPeriod: (UsagePeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SegmentedButton(
            selected = period == UsagePeriod.Week,
            onClick = { onSetPeriod(UsagePeriod.Week) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.period_week)) }
        SegmentedButton(
            selected = period == UsagePeriod.Day,
            onClick = { onSetPeriod(UsagePeriod.Day) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.period_day)) }
    }
}

@Composable
private fun DetailUsageCard(state: AppDetailsUiState, onStep: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.stat_total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatStat(state.totalMillis),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            UsageBarChart(
                bars = state.chart,
                averageMillis = state.averageMillis,
                labelEvery = if (state.period == UsagePeriod.Week) 1 else 4,
            )
            Spacer(Modifier.height(8.dp))
            DetailRangeStepper(state.period, state.anchorDate, onStep)
        }
    }
}

@Composable
private fun DetailRangeStepper(period: UsagePeriod, anchor: LocalDate, onStep: (Boolean) -> Unit) {
    val today = LocalDate.now()
    val canForward = if (period == UsagePeriod.Day) {
        anchor.isBefore(today)
    } else {
        weekStart(anchor).isBefore(weekStart(today))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailStepButton(enabled = true, forward = false, onStep = onStep)
        Text(
            text = rangeLabel(period, anchor),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        DetailStepButton(enabled = canForward, forward = true, onStep = onStep)
    }
}

@Composable
private fun DetailStepButton(enabled: Boolean, forward: Boolean, onStep: (Boolean) -> Unit) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.clickable { onStep(forward) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (forward) IconArrowForward(tint = tint) else IconBack(tint = tint)
    }
}

private val DETAILS_CHOICES = listOf(15, 30, 45, 60, 90, 120, 180, 240)
