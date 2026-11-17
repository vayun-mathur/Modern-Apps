package com.vayunmathur.clock.ui

import android.media.RingtoneManager
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.platform.AlarmActions
import com.vayunmathur.clock.platform.AlarmScheduler
import com.vayunmathur.clock.platform.AlarmUiState
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.library.ui.ringtonePickerIntent
import com.vayunmathur.library.ui.ringtonePickerResult
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.LocalTime

/** Binds [ClockViewModel] to the stateless [AlarmScreen]. */
@Composable
fun AlarmPage(backStack: NavBackStack<Route>, clockViewModel: ClockViewModel) {
    val alarms by clockViewModel.alarms.collectAsState()
    val context = LocalContext.current
    val soundTitle = stringResource(R.string.sound)
    val alarmScheduler = AlarmScheduler

    var pickingAlarmId by remember { mutableStateOf<Long?>(null) }
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = ringtonePickerResult(result.data)
            clockViewModel.alarms.value.firstOrNull { it.id == pickingAlarmId }?.let { target ->
                // Same reschedule requirement as setGradualVolumeSeconds below:
                // the fire intent carries the ringtone URI.
                val updated = target.copy(ringtoneUri = picked)
                if (updated.enabled) alarmScheduler.schedule(context, updated)
                clockViewModel.upsert(updated)
            }
        }
        pickingAlarmId = null
    }

    val actions = remember(clockViewModel, context) {
        object : AlarmActions {
            private fun save(alarm: Alarm) {
                // Snooze state belongs to the old schedule: re-arming the alarm
                // (new time, new days) drops any pending snooze with it.
                val cleared = if (alarm.snoozedUntil != null) alarm.copy(snoozedUntil = null) else alarm
                if (cleared.enabled) alarmScheduler.schedule(context, cleared)
                clockViewModel.upsert(cleared)
            }

            override fun setName(alarm: Alarm, name: String) {
                // The fire intent carries the label; reschedule like any sound-affecting edit.
                save(alarm.copy(name = name))
            }
            override fun setTime(alarm: Alarm, time: LocalTime) = save(alarm.copy(time = time))
            override fun setDays(alarm: Alarm, days: Int) = save(alarm.copy(days = days))

            override fun setEnabled(alarm: Alarm, enabled: Boolean) {
                // Toggling off also drops a pending snooze: the user silenced the alarm.
                val updated = alarm.copy(
                    enabled = enabled,
                    snoozedUntil = if (enabled) alarm.snoozedUntil else null,
                )
                if (enabled) alarmScheduler.schedule(context, updated) else alarmScheduler.cancel(context, updated)
                clockViewModel.upsert(updated)
            }

            override fun delete(alarm: Alarm) {
                alarmScheduler.cancel(context, alarm)
                clockViewModel.delete(alarm)
            }

            override fun pickRingtone(alarm: Alarm) {
                pickingAlarmId = alarm.id
                ringtoneLauncher.launch(
                    ringtonePickerIntent(
                        alarm.ringtoneUri,
                        RingtoneManager.TYPE_ALARM,
                        soundTitle,
                    )
                )
            }

            override fun setVibrate(alarm: Alarm, vibrate: Boolean) {
                // The fire intent carries vibrate/ringtone so the receiver can ring
                // without a database read; a plain upsert would leave the already-
                // scheduled intent (and the snooze path) holding the old values.
                save(alarm.copy(vibrate = vibrate))
            }

            override fun setSnoozeMinutes(alarm: Alarm, minutes: Int) {
                clockViewModel.upsert(alarm.copy(snoozeMinutes = minutes))
            }

            override fun setGradualVolumeSeconds(alarm: Alarm, seconds: Int) {
                // Must reschedule, not just upsert: the pending broadcast carries this value so
                // AlarmReceiver can pick a channel without a database read, and a plain upsert
                // would leave the already-scheduled intent holding the old one.
                save(alarm.copy(gradualVolumeSeconds = seconds))
            }
        }
    }

    AlarmScreen(
        backStack = backStack,
        state = AlarmUiState(alarms = alarms, is24Hour = DateFormat.is24HourFormat(context)),
        actions = actions,
    )
}
