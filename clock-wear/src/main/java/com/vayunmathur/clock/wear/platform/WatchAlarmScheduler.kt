package com.vayunmathur.clock.wear.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vayunmathur.clock.wear.domain.ArmAttempt
import com.vayunmathur.clock.wear.domain.ArmFailureRecord
import com.vayunmathur.clock.wear.domain.ArmedAlarmRecord
import com.vayunmathur.clock.wear.domain.DisplayOnlyReason
import com.vayunmathur.clock.wear.domain.WearClockAlarm
import com.vayunmathur.clock.wear.domain.WearClockSnapshot
import com.vayunmathur.clock.wear.domain.nextWearAlarmTriggerMillis
import com.vayunmathur.clock.wear.ui.WearMainActivity
import com.vayunmathur.library.log.Log
import kotlinx.datetime.TimeZone

/**
 * Arms synced alarms with the *watch's own* AlarmManager. An alarm counts
 * as armed only when one of these calls returned without throwing; the
 * [ArmedAlarmRecord] written then is the proof the UI renders from.
 */
object WatchAlarmScheduler {
    private const val TAG = "WatchAlarmScheduler"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_LABEL = "alarm_label"
    const val EXTRA_IS_SNOOZE = "is_snooze"

    /** Whether the watch may schedule exact alarms right now (API 31+ gate). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    /** Arm [alarm]'s next occurrence from [nowMillis]; never throws. */
    fun schedule(context: Context, alarm: WearClockAlarm, nowMillis: Long): ArmAttempt {
        val trigger = nextWearAlarmTriggerMillis(
            alarm.hour, alarm.minute, alarm.daysMask, nowMillis, TimeZone.currentSystemDefault(),
        )
        return scheduleAt(context, alarm.alarmId, alarm.label, trigger, isSnooze = false)
    }

    /** Arm a one-off snooze ring for [alarmId] in [snoozeMinutes]. */
    fun scheduleSnooze(context: Context, alarmId: Long, label: String, snoozeMinutes: Int, nowMillis: Long): ArmAttempt =
        scheduleAt(
            context, alarmId, label,
            nowMillis + snoozeMinutes.coerceAtLeast(1) * 60_000L, isSnooze = true,
        )

    /** Arm one explicit trigger; exact first, inexact while-idle fallback, failure last. */
    fun scheduleAt(
        context: Context,
        alarmId: Long,
        label: String,
        triggerMillis: Long,
        isSnooze: Boolean,
    ): ArmAttempt {
        val manager = context.getSystemService(AlarmManager::class.java)
            ?: return ArmAttempt.Failed(DisplayOnlyReason.SCHEDULING_FAILED)
        val pending = ringPendingIntent(context, alarmId, label, isSnooze, create = true)!!
        if (canScheduleExact(context)) {
            try {
                val show = PendingIntent.getActivity(
                    context, alarmId.toInt(),
                    Intent(context, WearMainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerMillis, show), pending)
                return ArmAttempt.Armed(ArmedAlarmRecord(alarmId, triggerMillis, exact = true))
            } catch (e: SecurityException) {
                Log.status(TAG, "exact arm for alarm $alarmId denied, falling back: ${e.message}")
            }
        }
        return try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
            ArmAttempt.Armed(ArmedAlarmRecord(alarmId, triggerMillis, exact = false))
        } catch (e: SecurityException) {
            Log.status(TAG, "inexact arm for alarm $alarmId denied: ${e.message}")
            ArmAttempt.Failed(DisplayOnlyReason.SCHEDULING_FAILED)
        }
    }

    /** Cancel ring + snooze schedules for [alarmId]. */
    fun cancel(context: Context, alarmId: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        for (isSnooze in listOf(false, true)) {
            ringPendingIntent(context, alarmId, "", isSnooze, create = false)?.let {
                manager.cancel(it)
                it.cancel()
            }
        }
    }

    /**
     * Reconcile AlarmManager with [snapshot]: arm every enabled alarm,
     * cancel disabled and previously-armed-but-removed ones. Returns the
     * arming proof + failures to persist.
     */
    fun reconcile(
        context: Context,
        snapshot: WearClockSnapshot,
        previouslyArmedIds: Collection<Long>,
        nowMillis: Long,
    ): Pair<List<ArmedAlarmRecord>, List<ArmFailureRecord>> {
        val presentIds = snapshot.alarms.map { it.alarmId }.toSet()
        for (removedId in previouslyArmedIds - presentIds) cancel(context, removedId)
        val armed = mutableListOf<ArmedAlarmRecord>()
        val failures = mutableListOf<ArmFailureRecord>()
        for (alarm in snapshot.alarms) {
            if (!alarm.enabled) {
                cancel(context, alarm.alarmId)
                continue
            }
            when (val attempt = schedule(context, alarm, nowMillis)) {
                is ArmAttempt.Armed -> armed += attempt.record.copy(
                    snapshotGeneration = snapshot.generatedAtMillis,
                )
                is ArmAttempt.Failed -> failures += ArmFailureRecord(alarm.alarmId, attempt.reason)
            }
        }
        return armed to failures
    }

    private fun ringPendingIntent(
        context: Context,
        alarmId: Long,
        label: String,
        isSnooze: Boolean,
        create: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, WatchAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_IS_SNOOZE, isSnooze)
        }
        val code = alarmId.toInt() * 2 + if (isSnooze) 1 else 0
        return PendingIntent.getBroadcast(
            context, code, intent,
            if (create) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            },
        )
    }
}
