package com.vayunmathur.todo.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vayunmathur.todo.domain.DueDates
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/** One daily alarm for the "N today, M overdue" digest; re-armed each time it fires. */
object DigestScheduler {
    const val ACTION_DIGEST = "com.vayunmathur.todo.action.DIGEST"
    private const val REQUEST_CODE = 0x7D16E5
    private const val MINUTES_PER_HOUR = 60

    fun schedule(context: Context, enabled: Boolean, minutesOfDay: Long, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context)
        if (!enabled) {
            alarms.cancel(pi)
            return
        }
        alarms.arm(nextTrigger(minutesOfDay, now, TimeZone.currentSystemDefault()), pi)
    }

    fun nextTrigger(minutesOfDay: Long, now: Long, zone: TimeZone): Long {
        val time = LocalTime((minutesOfDay / MINUTES_PER_HOUR).toInt(), (minutesOfDay % MINUTES_PER_HOUR).toInt())
        val today = DueDates.localDate(now, zone)
        val todayAt = DueDates.at(today, time, zone)
        return if (todayAt > now) todayAt else DueDates.at(today.plus(1, DateTimeUnit.DAY), time, zone)
    }

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_DIGEST),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
