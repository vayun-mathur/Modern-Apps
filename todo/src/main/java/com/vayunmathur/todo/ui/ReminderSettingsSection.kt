package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.SpecialAccess
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.todo.R
import kotlinx.datetime.LocalTime

/** Daily digest on/off and time, plus a shortcut to exact-alarm access when it is missing. */
@Composable
fun ReminderSettingsSection(
    digestEnabled: Boolean,
    digestMinutes: Long,
    onDigestEnabled: (Boolean) -> Unit,
    onDigestMinutes: (Long) -> Unit,
) {
    val context = LocalContext.current
    val requestPermission = rememberNotificationPermissionRequest()
    var pickTime by remember { mutableStateOf(false) }
    val time = LocalTime((digestMinutes / MINUTES_PER_HOUR).toInt(), (digestMinutes % MINUTES_PER_HOUR).toInt())
    SettingsSection(title = stringResource(R.string.settings_reminders)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_digest),
            supportingText = stringResource(R.string.settings_digest_summary),
            checked = digestEnabled,
            onCheckedChange = {
                if (it) requestPermission()
                onDigestEnabled(it)
            },
        )
        SettingsRow(
            title = stringResource(R.string.settings_digest_time),
            supportingText = DateString.time(time, rememberIs24Hour()),
            enabled = digestEnabled,
            onClick = { pickTime = true },
        )
        if (!SpecialAccess.hasExactAlarms(context)) {
            SettingsRow(
                title = stringResource(R.string.settings_exact_alarms),
                supportingText = stringResource(R.string.settings_exact_alarms_summary),
                onClick = { SpecialAccess.requestExactAlarms(context) },
            )
        }
    }
    SettingsDivider()
    if (pickTime) {
        TodoTimePickerDialog(initial = time, onConfirm = {
            onDigestMinutes(it.hour.toLong() * MINUTES_PER_HOUR + it.minute)
            pickTime = false
        }, onDismiss = { pickTime = false })
    }
}

private const val MINUTES_PER_HOUR = 60
