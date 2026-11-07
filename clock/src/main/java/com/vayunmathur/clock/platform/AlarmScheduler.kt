package com.vayunmathur.clock.platform
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockRepository
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

object AlarmScheduler {
    /**
     * Whether this alarm fades in, carried in the broadcast so AlarmReceiver can pick a channel
     * without a database read. Absent means zero, which is the fail-safe answer: an alarm we
     * know nothing about gets the insistent ring rather than silently losing it.
     */
    const val EXTRA_GRADUAL_SECONDS = "GRADUAL_SECONDS"
    const val EXTRA_LABEL = "ALARM_LABEL"
    const val EXTRA_RINGTONE_URI = "RINGTONE_URI"
    const val EXTRA_VIBRATE = "VIBRATE"

    /**
     * Marks the intent as the fire of a previously persisted snooze (as opposed to the
     * alarm's regular schedule). [AlarmReceiver] clears the stored snooze when it sees
     * this, so a regular fire does not clear a pending snooze and a snooze fire does.
     */
    const val EXTRA_IS_SNOOZE = "IS_SNOOZE"

    /** Distinct request codes so a snooze never overwrites the alarm's own schedule. */
    internal const val REQUEST_RING = 0
    internal const val REQUEST_SNOOZE = 1

    fun schedule(context: Context, alarm: Alarm) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
            putExtra(EXTRA_GRADUAL_SECONDS, alarm.gradualVolumeSeconds)
            putExtra(EXTRA_LABEL, alarm.name)
            putExtra(EXTRA_RINGTONE_URI, alarm.ringtoneUri)
            putExtra(EXTRA_VIBRATE, alarm.vibrate)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ringRequestCode(alarm.id),
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
        cancelRing(context, alarmManager, alarm.id)
        cancelSnooze(context, alarmManager, alarm.id)
    }

    /**
     * Arms the snoozed ring as its own AlarmManager entry, on a distinct request
     * code so it cannot clobber the alarm's recurring schedule, and persists it
     * so [BootReceiver] can re-arm it after a reboot.
     */
    suspend fun scheduleSnooze(
        context: Context,
        repository: ClockRepository,
        alarm: Alarm,
        snoozeMinutes: Int,
    ): Long {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerMillis = Clock.System.now().plus(
            snoozeMinutes.coerceAtLeast(1).minutes
        ).toEpochMilliseconds()
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarm.id)
            putExtra(EXTRA_IS_SNOOZE, true)
            // Snooze builds its own intent rather than going through schedule(), so it has
            // to carry these itself or the snoozed alarm would ring with the wrong settings.
            putExtra(EXTRA_GRADUAL_SECONDS, alarm.gradualVolumeSeconds)
            putExtra(EXTRA_LABEL, alarm.name)
            putExtra(EXTRA_RINGTONE_URI, alarm.ringtoneUri)
            putExtra(EXTRA_VIBRATE, alarm.vibrate)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(alarm.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerMillis, pendingIntent), pendingIntent)
        repository.upsertAlarm(
            alarm.copy(snoozedUntil = Instant.fromEpochMilliseconds(triggerMillis))
        )
        return triggerMillis
    }

    /** Drops the persisted snooze without touching the alarm's own schedule. */
    suspend fun clearSnooze(
        context: Context,
        repository: ClockRepository,
        alarm: Alarm,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        cancelSnooze(context, alarmManager, alarm.id)
        if (alarm.snoozedUntil != null) {
            repository.upsertAlarm(alarm.copy(snoozedUntil = null))
        }
    }

    internal fun ringRequestCode(alarmId: Long): Int = alarmId.toInt() * 2 + REQUEST_RING

    internal fun snoozeRequestCode(alarmId: Long): Int = alarmId.toInt() * 2 + REQUEST_SNOOZE

    private fun cancelRing(context: Context, alarmManager: AlarmManager, alarmId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ringRequestCode(alarmId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun cancelSnooze(context: Context, alarmManager: AlarmManager, alarmId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(alarmId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    fun calculateNextTriggerMillis(alarm: Alarm): Long =
        calculateNextTriggerMillis(alarm, Clock.System.now(), TimeZone.currentSystemDefault())

    /**
     * Next fire instant for [alarm] at or after [now] in [timeZone].
     *
     * Day stepping is calendar-based (next local date at the same wall-clock
     * time), never a fixed 24h duration: adding 24h to an instant lands an
     * hour late across the spring DST transition and an hour early across
     * the autumn one, and also shifts the weekday the repeat mask is tested
     * against near midnight. [LocalDateTime.toInstant] resolves a wall time
     * inside a DST gap forward and an overlap to the earlier offset.
     */
    internal fun calculateNextTriggerMillis(alarm: Alarm, now: Instant, timeZone: TimeZone): Long {
        val today = now.toLocalDateTime(timeZone).date

        // 1. Create a LocalDateTime for today at the alarm time
        var candidate = LocalDateTime(today, alarm.time)

        // 2. Initial check: if the time has already passed today,
        // we must start looking from tomorrow
        if (candidate.toInstant(timeZone) <= now) {
            candidate = candidate.nextDay(alarm)
        }

        // 3. Scenario A: No days selected (One-shot alarm)
        if (alarm.days == 0) {
            return candidate.toInstant(timeZone).toEpochMilliseconds()
        }

        // 4. Scenario B: Recurring days (Find the next matching day)
        // We check up to 7 days in the future
        repeat(DAYS_IN_WEEK) {
            val dayOfWeek = candidate.dayOfWeek // kotlinx.datetime.DayOfWeek

            // Map kotlinx DayOfWeek (Mon=1...Sun=7) to your bitmask (Sun=0...Sat=6)
            val bit = if (dayOfWeek == DayOfWeek.SUNDAY) 0 else dayOfWeek.isoDayNumber

            if ((alarm.days and (1 shl bit)) != 0) {
                return candidate.toInstant(timeZone).toEpochMilliseconds()
            }

            // Move to the next day and check again
            candidate = candidate.nextDay(alarm)
        }

        // Fallback (should be unreachable if bits are set)
        return candidate.toInstant(timeZone).toEpochMilliseconds()
    }

    /** Same wall-clock time on the next calendar date. */
    private fun LocalDateTime.nextDay(alarm: Alarm): LocalDateTime =
        LocalDateTime(date.plus(1, DateTimeUnit.DAY), alarm.time)

    private const val DAYS_IN_WEEK = 7
}
