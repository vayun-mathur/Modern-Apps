package com.vayunmathur.calendar.platform

import android.app.AlarmManager
import androidx.core.content.edit
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.data.ReminderMirror
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.AppMessages
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Schedules exact alarms for calendar event reminders, mirroring the clock
 * app's [com.vayunmathur.clock.util.AlarmScheduler].
 *
 * Reminders live in `CalendarContract.Reminders` but the provider never fires
 * them itself, so we translate each reminder offset into an [AlarmManager]
 * exact alarm that broadcasts to [ReminderReceiver].
 *
 * Both one-off and recurring events are supported:
 *  - One-off events schedule every future reminder offset directly.
 *  - Recurring events schedule the next upcoming occurrence per reminder
 *    offset (expanded via [Instance.getInstances], which already honors
 *    EXDATE). When one fires, [ReminderReceiver] re-runs [reconcileAll] so the
 *    following occurrence gets scheduled. Boot does the same.
 *
 * [reconcileAll] is a cancel-all-then-reschedule-all pass, which keeps state
 * correct across insert / update / delete / exdate without per-mutation
 * bookkeeping. The set of currently-scheduled request codes is persisted so a
 * later reconcile can cancel alarms belonging to events that no longer exist.
 *
 * Because the provider is unreadable before the first unlock after a reboot,
 * [reconcileAll] also writes a small [ReminderMirror] to device-protected storage,
 * and [scheduleFromMirror] re-arms alarms from it at ACTION_LOCKED_BOOT_COMPLETED.
 */
object ReminderScheduler {
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_MINUTES = "minutes"
    const val EXTRA_INSTANCE_START = "instance_start"
    const val EXTRA_INSTANCE_END = "instance_end"
    const val EXTRA_TITLE = "title"
    /**
     * Full Instance JSON for the notification tap deep-link. Same key the
     * agenda widget uses, read by MainActivity.consumeInstanceLink.
     */
    const val EXTRA_INSTANCE = "instance"

    private const val PREFS = "calendar_reminders"
    private const val KEY_SCHEDULED = "scheduled_request_codes"

    /** Milliseconds in a minute, for reminder-offset arithmetic. */
    private const val MILLIS_PER_MINUTE = 60_000L

    /** Hash factor for deriving stable alarm request codes. */
    private const val HASH_FACTOR = 31

    // How far ahead to look for the next occurrence of a recurring event.
    private val RECURRENCE_WINDOW = 400.days

    // How far ahead the device-protected mirror reaches. The mirror only has to cover
    // the gap between the last unlocked reconcile and the first unlock after a reboot,
    // and it is rewritten on every reconcile, so a week is generous - it survives a
    // phone left off over a long weekend. Keeping it short also bounds both the size of
    // the blob and how much scheduling metadata sits in unencrypted storage.
    private val MIRROR_WINDOW = 7.days

    /** Convenience for boot / package-replaced: reschedule from the provider. */
    suspend fun reconcileAll(context: Context) =
        reconcileAll(context, Event.getAllEvents(context))

    suspend fun reconcileAll(context: Context, events: List<Event>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        cancelAllTracked(context, alarmManager)

        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val scheduled = mutableSetOf<Int>()
        val mirrorHorizon = nowMillis + MIRROR_WINDOW.inWholeMilliseconds
        val mirrored = mutableListOf<ReminderMirror.Entry>()

        for (event in events) {
            scheduleEventReminders(context, alarmManager, event, nowMillis, mirrorHorizon, scheduled, mirrored)
        }
        saveTracked(context, scheduled)
        ReminderMirror.write(context, mirrored)
    }

    /** Schedules every future reminder offset of one event; no-ops without an id or offsets. */
    private fun scheduleEventReminders(
        context: Context,
        alarmManager: AlarmManager,
        event: Event,
        nowMillis: Long,
        mirrorHorizon: Long,
        scheduled: MutableSet<Int>,
        mirrored: MutableList<ReminderMirror.Entry>,
    ) {
        val eventId = event.id ?: return
        val offsets = event.reminders.distinct()
        if (offsets.isEmpty()) return
        if (!event.isRecurring) {
            scheduleOneOffReminders(context, alarmManager, event, eventId, offsets, nowMillis,
                mirrorHorizon, scheduled, mirrored)
        } else {
            scheduleRecurringReminders(context, alarmManager, event, eventId, offsets, nowMillis,
                mirrorHorizon, scheduled, mirrored)
        }
    }

    private fun scheduleOneOffReminders(
        context: Context,
        alarmManager: AlarmManager,
        event: Event,
        eventId: Long,
        offsets: List<Int>,
        nowMillis: Long,
        mirrorHorizon: Long,
        scheduled: MutableSet<Int>,
        mirrored: MutableList<ReminderMirror.Entry>,
    ) {
        for (minutes in offsets) {
            val triggerAt = event.start - minutes.toLong() * MILLIS_PER_MINUTE
            if (triggerAt <= nowMillis) continue
            armReminder(context, alarmManager, eventId, minutes, event.start, event.end, event.title,
                triggerAt, mirrorHorizon, scheduled, mirrored)
        }
    }

    private fun scheduleRecurringReminders(
        context: Context,
        alarmManager: AlarmManager,
        event: Event,
        eventId: Long,
        offsets: List<Int>,
        nowMillis: Long,
        mirrorHorizon: Long,
        scheduled: MutableSet<Int>,
        mirrored: MutableList<ReminderMirror.Entry>,
    ) {
        val now = Clock.System.now()
        val instances = Instance.getInstances(context, now, now + RECURRENCE_WINDOW)
            .filter { it.eventID == eventId }
            .sortedBy { it.begin }
        for (minutes in offsets) {
            val next = instances.firstOrNull {
                it.begin - minutes.toLong() * MILLIS_PER_MINUTE > nowMillis
            } ?: continue
            val triggerAt = next.begin - minutes.toLong() * MILLIS_PER_MINUTE
            armReminder(context, alarmManager, eventId, minutes, next.begin, next.end, event.title,
                triggerAt, mirrorHorizon, scheduled, mirrored)
        }
    }

    /** Arms one exact alarm and records it in the tracked set (+ mirror when in range). */
    private fun armReminder(
        context: Context,
        alarmManager: AlarmManager,
        eventId: Long,
        minutes: Int,
        instanceStart: Long,
        instanceEnd: Long,
        title: String,
        triggerAt: Long,
        mirrorHorizon: Long,
        scheduled: MutableSet<Int>,
        mirrored: MutableList<ReminderMirror.Entry>,
    ) {
        val code = requestCode(eventId, minutes, instanceStart)
        scheduleExact(
            context, alarmManager, code, triggerAt,
            eventId, minutes, instanceStart, instanceEnd, title,
        )
        scheduled += code
        if (triggerAt <= mirrorHorizon) {
            mirrored += ReminderMirror.Entry(eventId, minutes, instanceStart, instanceEnd)
        }
    }

    /**
     * Re-arm alarms at ACTION_LOCKED_BOOT_COMPLETED, when the calendar provider cannot be
     * read. Returns how many were armed.
     *
     * Deliberately does not cancel or track anything: [loadTracked] / [saveTracked] live in
     * credential-encrypted storage and are unreachable here. It does not need to. The mirror
     * is written by the same [reconcileAll] pass that writes the tracked set and is a subset
     * of it, so every code armed here is already tracked and will be cancelled by the next
     * post-unlock [cancelAllTracked] - which is what makes deletions that happened while the
     * phone was off resolve correctly. Re-arming is otherwise idempotent: request codes are
     * derived from the event, so a second locked boot just replaces the same alarms.
     *
     * The mirror carries no title, so pre-unlock notifications fall back to the generic
     * string. See [ReminderMirror] for why.
     */
    suspend fun scheduleFromMirror(context: Context): Int {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return 0
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        var armed = 0
        for (entry in ReminderMirror.read(context)) {
            val triggerAt = entry.instanceStart - entry.minutes.toLong() * 60_000L
            if (triggerAt <= nowMillis) continue
            scheduleExact(
                context,
                alarmManager,
                requestCode(entry.eventId, entry.minutes, entry.instanceStart),
                triggerAt,
                entry.eventId,
                entry.minutes,
                entry.instanceStart,
                entry.instanceEnd,
                title = null,
            )
            armed++
        }
        return armed
    }

    private fun scheduleExact(
        context: Context,
        alarmManager: AlarmManager,
        requestCode: Int,
        triggerAtMillis: Long,
        eventId: Long,
        minutes: Int,
        instanceStart: Long,
        instanceEnd: Long,
        title: String?,
    ) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_MINUTES, minutes)
            putExtra(EXTRA_INSTANCE_START, instanceStart)
            putExtra(EXTRA_INSTANCE_END, instanceEnd)
            if (title != null) putExtra(EXTRA_TITLE, title)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The exact-alarm gate UI lives in MainActivity's AppPermissionsGate
        // (PermissionRequirement.ExactAlarms -> SpecialAccess.requestExactAlarms); this stays
        // the runtime backstop and must not duplicate that flow. canScheduleExactAlarms() is
        // checked on S+ so a denial surfaces via messenger instead of silently degrading to
        // inexact; the SecurityException catch remains for the revoked-mid-scheduling race.
        if (!alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            AppMessages.show(context.getString(UiR.string.permission_exact_alarms_rationale))
            return
        }
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent,
            )
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            AppMessages.show(context.getString(UiR.string.permission_exact_alarms_rationale))
        }
    }

    private fun cancelAllTracked(context: Context, alarmManager: AlarmManager) {
        val codes = loadTracked(context)
        for (code in codes) {
            val intent = Intent(context, ReminderReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }
    }

    private fun requestCode(eventId: Long, minutes: Int, instanceStart: Long): Int {
        var result = eventId.hashCode()
        result = HASH_FACTOR * result + minutes
        result = HASH_FACTOR * result + instanceStart.hashCode()
        return result
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadTracked(context: Context): Set<Int> =
        prefs(context).getStringSet(KEY_SCHEDULED, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

    private fun saveTracked(context: Context, codes: Set<Int>) {
        prefs(context).edit {
            putStringSet(KEY_SCHEDULED, codes.map { it.toString() }.toSet())
        }
    }
}
