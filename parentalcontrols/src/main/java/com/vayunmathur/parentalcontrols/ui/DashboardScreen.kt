package com.vayunmathur.parentalcontrols.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconBedtime
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconHourglass
import com.vayunmathur.library.ui.IconKey
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.parentalcontrols.R
import com.vayunmathur.parentalcontrols.platform.SupervisionUiState

/** Actions the parental-controls dashboard can take. */
data class DashboardActions(
    val onSetControls: (Boolean) -> Unit,
    val onOpenDailyLimit: () -> Unit,
    val onOpenAppLimits: () -> Unit,
    val onOpenDowntime: () -> Unit,
    val onOpenWebFilters: () -> Unit,
    val onOpenPin: () -> Unit,
)

/** The top-level parental-controls page shown from Settings: a master switch over the controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(state: SupervisionUiState, actions: DashboardActions) {
    LazyListScaffold(
        title = stringResource(R.string.dashboard_title),
        horizontalPadding = 0.dp,
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.largeIncreased,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.controls_for_this_phone),
                    checked = state.controlsEnabled,
                    onCheckedChange = actions.onSetControls,
                )
            }
        }

        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.daily_title),
                    supportingText = dailyLimitSummary(state),
                    leadingContent = { IconTimer() },
                    onClick = actions.onOpenDailyLimit,
                )
                SettingsRow(
                    title = stringResource(R.string.limits_title),
                    supportingText = appLimitsSummary(state),
                    leadingContent = { IconHourglass() },
                    onClick = actions.onOpenAppLimits,
                )
                SettingsRow(
                    title = stringResource(R.string.downtime_title),
                    supportingText = downtimeSummary(state),
                    leadingContent = { IconBedtime() },
                    onClick = actions.onOpenDowntime,
                )
                SettingsRow(
                    title = stringResource(R.string.dashboard_web_filters),
                    supportingText = stringResource(R.string.summary_web_filters),
                    leadingContent = { IconGlobe() },
                    onClick = actions.onOpenWebFilters,
                )
            }
        }

        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.dashboard_manage_pin),
                    leadingContent = { IconKey() },
                    onClick = actions.onOpenPin,
                )
            }
        }
    }
}

@Composable
private fun dailyLimitSummary(state: SupervisionUiState): String =
    state.dailyLimitMinutes?.let { formatLimit(it.toLong()) }
        ?: stringResource(R.string.summary_not_set)

@Composable
private fun appLimitsSummary(state: SupervisionUiState): String =
    if (state.apps.any { it.rule?.dailyLimitMinutes != null }) {
        stringResource(R.string.summary_app_limits_set)
    } else {
        stringResource(R.string.summary_no_app_limits)
    }

@Composable
private fun downtimeSummary(state: SupervisionUiState): String =
    if (state.downtime.enabled) {
        "${clock(state.downtime.startMinute)} – ${clock(state.downtime.endMinute)}"
    } else {
        stringResource(R.string.summary_off)
    }

/** 12-hour clock label for a minute-of-day, e.g. 1260 -> "9:00 PM". */
private fun clock(minuteOfDay: Int): String {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    val suffix = if (hour < 12) "AM" else "PM"
    val display = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "%d:%02d %s".format(display, minute, suffix)
}

/** Whole-minute limit label: `45 min` or `1 h 30 min`. */
private fun formatLimit(minutes: Long): String =
    if (minutes < 60) {
        "$minutes min"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0L) "$hours h" else "$hours h $rest min"
    }
