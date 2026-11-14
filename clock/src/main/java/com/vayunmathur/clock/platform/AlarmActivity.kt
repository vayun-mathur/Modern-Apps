package com.vayunmathur.clock.platform
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.clock.R
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.ui.components.AlarmRingingScreen
import com.vayunmathur.clock.ui.components.formatAlarmTime
import com.vayunmathur.clock.service.AlarmSoundService
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen ringing screen shown when an alarm fires, offering dismiss and snooze. */
class AlarmActivity : ComponentActivity() {
    private var alarmId: Long = -1L
    private var alarm: Alarm? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true); setTurnScreenOn(true); super.onCreate(savedInstanceState)
        alarmId = intent.getLongExtra("ALARM_ID", -1L)
        loadAlarm(intent)
        setContent {
            DynamicTheme {
                AlarmRingingScreen(
                    alarmTime = alarm?.let { formatAlarmTime(this@AlarmActivity, it.time) } ?: "--:--",
                    alarmName = alarm?.name ?: getString(R.string.label_alarm),
                    // The alarm is loaded async; read it off state (not a field
                    // assigned in a LaunchedEffect after composition) so the first
                    // draw already shows the real value once loaded.
                    snoozeMinutes = alarm?.snoozeMinutes ?: 5,
                    snoozeEnabled = alarm != null,
                    onDismiss = { dismissAlarm() },
                    onSnooze = { snoozeAlarm() }
                )
            }
        }
    }

    /**
     * singleInstance reuses this activity for a second alarm fired while the
     * first is still showing; without this the dismiss/snooze buttons would
     * keep acting on the stale id and the UI would show the wrong alarm.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alarmId = intent.getLongExtra("ALARM_ID", -1L)
        loadAlarm(intent)
    }

    private fun loadAlarm(intent: Intent) {
        val id = intent.getLongExtra("ALARM_ID", -1L)
        if (id == -1L) {
            Log.error(TAG, "Ringing activity started without an alarm id")
            finish()
            return
        }
        alarm = null
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ClockRepository.get(applicationContext).getAlarm(id) }
                .onSuccess { loaded -> withContext(Dispatchers.Main) { alarm = loaded } }
                .onFailure {
                    Log.error(TAG, "Alarm $id: could not load ringing alarm", it)
                    withContext(Dispatchers.Main) { finish() }
                }
        }
    }

    private fun dismissAlarm() {
        val ringing = alarm
        stopService(Intent(this, AlarmSoundService::class.java))
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(ALARM_RING_NOTIFICATION_ID); finish()
        if (ringing != null) {
            // Dismissing also drops any pending snooze: the user stopped the alarm,
            // they did not ask to be woken again in five minutes.
            val ctx = this
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    AlarmScheduler.clearSnooze(ctx, ClockRepository.get(ctx), ringing)
                }.onFailure {
                    Log.error(TAG, "Alarm ${ringing.id}: could not clear snooze on dismiss", it)
                }
            }
        }
    }

    private fun snoozeAlarm() {
        // Snooze is disabled until the alarm loads (snoozeEnabled above), but a
        // stale click could still land here; guard rather than schedule garbage.
        val ringing = alarm ?: return
        // Stop the sound first so the snooze tap never overlaps the old ring, and
        // clear the insistent notification the receiver posted: it keeps sounding
        // on the system side after the service dies.
        stopService(Intent(this, AlarmSoundService::class.java))
        getSystemService(NotificationManager::class.java).cancel(ALARM_RING_NOTIFICATION_ID)
        val ctx = this
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                AlarmScheduler.scheduleSnooze(
                    ctx,
                    ClockRepository.get(ctx),
                    ringing,
                    ringing.snoozeMinutes,
                )
            }.onFailure {
                Log.error(TAG, "Alarm ${ringing.id}: could not schedule snooze", it)
            }
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private companion object {
        const val TAG = "AlarmActivity"
    }
}
