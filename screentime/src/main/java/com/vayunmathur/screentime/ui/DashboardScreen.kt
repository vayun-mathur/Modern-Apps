package com.vayunmathur.screentime.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.screentime.R
import com.vayunmathur.screentime.platform.ScreenTimeUiState
import com.vayunmathur.screentime.platform.TimedApp
import com.vayunmathur.screentime.platform.UsagePeriod
import java.time.LocalDate

/** Actions the dashboard can take. */
data class DashboardActions(
    val onSetPeriod: (UsagePeriod) -> Unit,
    val onStep: (Boolean) -> Unit,
    val onOpenApp: (String) -> Unit,
)

/** The week/day usage chart plus a most-used-first app list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(state: ScreenTimeUiState, actions: DashboardActions) {
    val context = LocalContext.current
    val openUsageAccess = {
        // Some images ship a Settings build with no usage-access activity; a missing
        // handler must not crash the dashboard.
        try {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            // No settings screen to open on this image; the empty dashboard remains.
        }
    }
    val topUsed = remember(state.apps) { state.apps.maxOfOrNull { it.usedMillis } ?: 0L }

    LazyListScaffold(
        title = stringResource(R.string.app_name),
        horizontalPadding = 0.dp,
    ) {
        item { PeriodToggle(state.period, actions.onSetPeriod) }

        if (state.hasUsageAccess) {
            item { UsageCard(state, actions.onStep) }
        }

        when {
            state.loading -> item { StatusRow(stringResource(R.string.apps_loading)) }
            !state.hasUsageAccess -> item {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.dashboard_no_access_title),
                        supportingText = stringResource(R.string.dashboard_no_access_hint),
                        onClick = { openUsageAccess() },
                    )
                    SettingsRow(
                        title = stringResource(R.string.dashboard_no_access_action),
                        onClick = { openUsageAccess() },
                    )
                }
            }
            state.apps.isEmpty() -> item {
                SettingsSection {
                    if (!state.installedLoaded) {
                        SettingsRow(
                            title = stringResource(R.string.dashboard_no_apps_title),
                            supportingText = stringResource(R.string.dashboard_no_apps_hint),
                        )
                    } else {
                        SettingsRow(
                            title = stringResource(R.string.dashboard_no_usage_title),
                            supportingText = stringResource(R.string.dashboard_no_usage_hint),
                        )
                    }
                }
            }
        }

        items(state.apps.size, key = { state.apps[it].packageName }) { index ->
            val app = state.apps[index]
            AppUsageRow(app, topUsed, onClick = { actions.onOpenApp(app.packageName) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodToggle(period: UsagePeriod, onSetPeriod: (UsagePeriod) -> Unit) {
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
private fun UsageCard(state: ScreenTimeUiState, onStep: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                StatColumn(
                    label = stringResource(R.string.stat_total),
                    value = formatStat(state.totalMillis),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                StatColumn(
                    label = if (state.period == UsagePeriod.Week) {
                        stringResource(R.string.stat_daily_average)
                    } else {
                        stringResource(R.string.stat_hourly_average)
                    },
                    value = formatStat(state.averageMillis),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            UsageBarChart(
                bars = state.chart,
                averageMillis = state.averageMillis,
                labelEvery = if (state.period == UsagePeriod.Week) 1 else 4,
            )
            Spacer(Modifier.height(8.dp))
            RangeStepper(state.period, state.anchorDate, onStep)
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RangeStepper(period: UsagePeriod, anchor: LocalDate, onStep: (Boolean) -> Unit) {
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
        StepButton(enabled = true, forward = false, onStep = onStep)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (period == UsagePeriod.Day && anchor == today) {
                Text(
                    text = stringResource(R.string.range_today),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = rangeLabel(period, anchor),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        StepButton(enabled = canForward, forward = true, onStep = onStep)
    }
}

@Composable
private fun StepButton(enabled: Boolean, forward: Boolean, onStep: (Boolean) -> Unit) {
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

@Composable
private fun AppUsageRow(app: TimedApp, topUsed: Long, onClick: () -> Unit) {
    val fraction = if (topUsed > 0L) app.usedMillis.toFloat() / topUsed.toFloat() else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconImage(
            packageName = app.packageName,
            modifier = Modifier.size(40.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatStat(app.usedMillis),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            UsageProgressBar(fraction)
        }
    }
}

@Composable
private fun UsageProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun StatusRow(text: String) {
    SettingsSection { SettingsRow(title = text) }
}
