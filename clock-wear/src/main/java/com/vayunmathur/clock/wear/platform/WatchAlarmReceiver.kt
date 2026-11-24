package com.vayunmathur.clock.wear.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.clock.wear.data.WearClockStore
import com.vayunmathur.clock.wear.domain.ArmAttempt

/**
 * A watch-armed alarm fired. Recurring alarms are re-armed for their next
 * occurrence before ringing (so a dismissed ring never loses tomorrow's);
 * one-shot alarms drop their arming proof — they will not ring again until
 * a future snapshot arms them anew. Snooze fires leave the regular
 * schedule's proof untouched.
 */
class WatchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(WatchAlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (alarmId < 0) return
        val label = intent.getStringExtra(WatchAlarmScheduler.EXTRA_LABEL).orEmpty()
        val isSnooze = intent.getBooleanExtra(WatchAlarmScheduler.EXTRA_IS_SNOOZE, false)
        val store = WearClockStore.get(context)
        if (!isSnooze) {
            val persisted = store.state.value
            val alarm = persisted.snapshot.alarms.firstOrNull { it.alarmId == alarmId }
            if (alarm != null && alarm.enabled && alarm.daysMask != 0) {
                when (val attempt = WatchAlarmScheduler.schedule(
                    context, alarm, System.currentTimeMillis(),
                )) {
                    is ArmAttempt.Armed -> store.setArming(
                        persisted.armedRecords.filterNot { it.alarmId == alarmId } +
                            attempt.record.copy(
                                snapshotGeneration = persisted.snapshot.generatedAtMillis,
                            ),
                        persisted.armFailures.filterNot { it.alarmId == alarmId },
                    )
                    is ArmAttempt.Failed -> store.setArming(
                        persisted.armedRecords.filterNot { it.alarmId == alarmId },
                        persisted.armFailures.filterNot { it.alarmId == alarmId } +
                            com.vayunmathur.clock.wear.domain.ArmFailureRecord(alarmId, attempt.reason),
                    )
                }
            } else if (alarm != null) {
                store.setArming(
                    persisted.armedRecords.filterNot { it.alarmId == alarmId },
                    persisted.armFailures.filterNot { it.alarmId == alarmId },
                )
            }
        }
        WatchRingNotifier.show(context, RingKind.ALARM, alarmId, label)
    }
}
