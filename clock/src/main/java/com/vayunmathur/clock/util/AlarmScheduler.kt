package com.vayunmathur.clock.util
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vayunmathur.clock.data.Alarm
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class AlarmScheduler {

    fun schedule(context: Context, alarm: Alarm) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(), // Unique ID per alarm
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Convert LocalTime to actual milliseconds for today or tomorrow
        val triggerTime = calculateNextTriggerMillis(alarm)

        // Use setAlarmClock so the user sees the alarm icon in their status bar
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
            pendingIntent
        )
    }

    fun cancel(context: Context, alarm: Alarm) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let { alarmManager.cancel(it) }
    }

    companion object {
        private var INSTANCE: AlarmScheduler? = null
        fun get(): AlarmScheduler {
            return INSTANCE ?: synchronized(this) {
                AlarmScheduler().also { INSTANCE = it }
            }
        }
    }

    fun calculateNextTriggerMillis(alarm: Alarm): Long {
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        val today = now.toLocalDateTime(timeZone).date

        // 1. Create a LocalDateTime for today at the alarm time
        var candidate = LocalDateTime(today, alarm.time)

        // 2. Initial check: if the time has already passed today,
        // we must start looking from tomorrow
        if (candidate.toInstant(timeZone) <= now) {
            candidate = candidate.toInstant(timeZone).plus(1.days).toLocalDateTime(timeZone)
        }

        // 3. Scenario A: No days selected (One-shot alarm)
        if (alarm.days == 0) {
            return candidate.toInstant(timeZone).toEpochMilliseconds()
        }

        // 4. Scenario B: Recurring days (Find the next matching day)
        // We check up to 7 days in the future
        repeat(7) {
            val dayOfWeek = candidate.dayOfWeek // kotlinx.datetime.DayOfWeek

            // Map kotlinx DayOfWeek (Mon=1...Sun=7) to your bitmask (Sun=0...Sat=6)
            val bit = if (dayOfWeek == DayOfWeek.SUNDAY) 0 else dayOfWeek.isoDayNumber

            if ((alarm.days and (1 shl bit)) != 0) {
                return candidate.toInstant(timeZone).toEpochMilliseconds()
            }

            // Move to the next day and check again
            candidate = candidate.toInstant(timeZone).plus(1.days).toLocalDateTime(timeZone)
        }

        // Fallback (should be unreachable if bits are set)
        return candidate.toInstant(timeZone).toEpochMilliseconds()
    }
}