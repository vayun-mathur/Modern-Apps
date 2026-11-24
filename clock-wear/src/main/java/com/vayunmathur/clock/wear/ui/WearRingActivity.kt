package com.vayunmathur.clock.wear.ui

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.clock.wear.R
import com.vayunmathur.clock.wear.data.WearClockStore
import com.vayunmathur.clock.wear.platform.RingKind
import com.vayunmathur.clock.wear.platform.WatchAlarmScheduler
import com.vayunmathur.clock.wear.platform.WatchRingNotifier

/**
 * The ringing surface: plays the default alarm tone and vibrates until
 * dismissed (or snoozed, for alarms). Opened via the full-screen
 * notification [WatchRingNotifier] posts when AlarmManager fires.
 */
class WearRingActivity : ComponentActivity() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = runCatching {
            RingKind.valueOf(intent.getStringExtra(WatchRingNotifier.EXTRA_KIND).orEmpty())
        }.getOrDefault(RingKind.ALARM)
        val id = intent.getLongExtra(WatchRingNotifier.EXTRA_ID, -1L)
        val label = intent.getStringExtra(WatchRingNotifier.EXTRA_LABEL).orEmpty()
        startRinging()
        val alarm = if (kind == RingKind.ALARM) {
            WearClockStore.get(this).state.value.snapshot.alarms.firstOrNull { it.alarmId == id }
        } else {
            null
        }
        setContent {
            MaterialTheme {
                ScalingLazyColumn {
                    item {
                        Text(
                            stringResource(
                                if (kind == RingKind.ALARM) R.string.wear_ringing_alarm
                                else R.string.wear_ringing_timer,
                            ),
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                    }
                    if (label.isNotBlank()) {
                        item {
                            Text(
                                label,
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            )
                        }
                    }
                    if (kind == RingKind.ALARM && alarm != null) {
                        item {
                            Chip(
                                onClick = {
                                    WatchAlarmScheduler.scheduleSnooze(
                                        this@WearRingActivity, id, label,
                                        alarm.snoozeMinutes, System.currentTimeMillis(),
                                    )
                                    stopAndFinish(id)
                                },
                                label = {
                                    Text(
                                        stringResource(R.string.wear_snooze, alarm.snoozeMinutes),
                                        maxLines = 1,
                                    )
                                },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            )
                        }
                    }
                    item {
                        Chip(
                            onClick = { stopAndFinish(id) },
                            label = { Text(stringResource(R.string.wear_dismiss), maxLines = 1) },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    private fun startRinging() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(this@WearRingActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        }
        vibrator = getSystemService(Vibrator::class.java)
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200), 0))
        }
    }

    private fun stopRinging() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
    }

    private fun stopAndFinish(id: Long) {
        stopRinging()
        if (id >= 0) WatchRingNotifier.dismiss(this, id)
        finish()
    }
}
