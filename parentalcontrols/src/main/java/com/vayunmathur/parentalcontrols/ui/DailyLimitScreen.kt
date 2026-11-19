package com.vayunmathur.parentalcontrols.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.parentalcontrols.R

/** Actions the device-wide daily-limit screen can take. */
data class DailyLimitActions(
    val onLimitChange: (Int?) -> Unit,
)

/**
 * The device-wide daily screen-time budget.
 *
 * One row, one dialog: when the budget is spent, everything except calls locks until midnight
 * (per-app caps keep working underneath). Same fixed choices as per-app limits, plus No limit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyLimitScreen(currentMinutes: Int?, actions: DailyLimitActions) {
    var picking by remember { mutableStateOf(false) }

    LazyListScaffold(
        title = stringResource(R.string.daily_title),
        horizontalPadding = 0.dp,
    ) {
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.daily_explainer),
                    supportingText = stringResource(R.string.daily_explainer_hint),
                )
                SettingsRow(
                    title = stringResource(R.string.daily_budget),
                    supportingText = currentMinutes?.let { formatDailyLimit(it) }
                        ?: stringResource(R.string.limits_none),
                    onClick = { picking = true },
                )
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.daily_budget)) },
            text = {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.limits_none),
                        onClick = { actions.onLimitChange(null); picking = false },
                    )
                    for (minutes in DAILY_CHOICES) {
                        SettingsRow(
                            title = formatDailyLimit(minutes),
                            onClick = { actions.onLimitChange(minutes); picking = false },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
        )
    }
}

/** Generous steps for a whole-device budget; per-app caps use the finer [CHOICES] scale. */
private val DAILY_CHOICES = listOf(60, 120, 180, 240, 300, 360, 480)

private fun formatDailyLimit(minutes: Int): String =
    if (minutes < 60) {
        "$minutes min"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) "$hours h" else "$hours h $rest min"
    }
