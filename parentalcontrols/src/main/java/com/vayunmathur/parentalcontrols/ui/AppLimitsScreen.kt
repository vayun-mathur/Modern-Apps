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
import com.vayunmathur.parentalcontrols.platform.SupervisableApp
import com.vayunmathur.parentalcontrols.platform.SupervisionUiState

/** Actions the app-limits screen can take. */
data class AppLimitsActions(
    val onLimitChange: (String, Int?) -> Unit,
)

/** A daily cap per app, in whole minutes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLimitsScreen(state: SupervisionUiState, actions: AppLimitsActions) {
    var editing by remember { mutableStateOf<SupervisableApp?>(null) }

    LazyListScaffold(
        title = stringResource(R.string.limits_title),
        horizontalPadding = 0.dp,
    ) {
        item {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.limits_explainer),
                    supportingText = stringResource(R.string.limits_explainer_hint),
                )
            }
        }

        if (state.apps.isEmpty()) {
            item { SettingsRow(title = stringResource(R.string.apps_loading)) }
        }

        items(state.apps.size, key = { state.apps[it].packageName }) { index ->
            val app = state.apps[index]
            val minutes = app.rule?.dailyLimitMinutes
            SettingsRow(
                title = app.label,
                supportingText = minutes?.let { formatLimit(it) }
                    ?: stringResource(R.string.limits_none),
                onClick = { editing = app },
            )
        }
    }

    val target = editing
    if (target != null) {
        LimitPickerDialog(
            app = target,
            onDismiss = { editing = null },
            onPick = { minutes ->
                actions.onLimitChange(target.packageName, minutes)
                editing = null
            },
        )
    }
}

/**
 * A fixed set of durations rather than free entry.
 *
 * The platform rejects anything over 24 h and anything negative, and a text field invites both.
 * These are the values a parent actually picks.
 */
@Composable
private fun LimitPickerDialog(
    app: SupervisableApp,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.limits_none),
                    onClick = { onPick(null) },
                )
                for (minutes in CHOICES) {
                    SettingsRow(
                        title = formatLimit(minutes),
                        onClick = { onPick(minutes) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

private val CHOICES = listOf(15, 30, 45, 60, 90, 120, 180, 240)

private fun formatLimit(minutes: Int): String =
    if (minutes < 60) {
        "$minutes min"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) "$hours h" else "$hours h $rest min"
    }
