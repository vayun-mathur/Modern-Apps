package com.vayunmathur.calendar.platform

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Instance
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** Builds a [Route.EditEvent] from an ACTION_INSERT intent's extras. */
internal fun editEventFromIntent(it: Intent): Route.EditEvent {
    return Route.EditEvent(
        id = null,
        title = it.getStringExtra(CalendarContract.Events.TITLE),
        description = it.getStringExtra(CalendarContract.Events.DESCRIPTION),
        location = it.getStringExtra(CalendarContract.Events.EVENT_LOCATION),
        beginTime = it.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L)
            .takeIf { value -> value != NO_TIME_MILLIS },
        endTime = it.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L)
            .takeIf { value -> value != NO_TIME_MILLIS },
        allDay = it.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            .takeIf { _ -> it.hasExtra(CalendarContract.EXTRA_EVENT_ALL_DAY) },
    )
}

internal const val NO_TIME_MILLIS = -1L

/**
 * Lightweight deep-link for a reminder tap when the full Instance JSON was
 * unavailable (pre-unlock alarm). Resolved against the provider once readable.
 */
data class ReminderFallback(val eventId: Long, val instanceStart: Long, val instanceEnd: Long)

/**
 * Time/date-jump VIEW intents. The provider often sends these with a null
 * type, so the URI shape is matched too: without this the date-jump never
 * runs and the URI falls into the ICS import path.
 */
internal fun isTimeEpochIntent(it: Intent): Boolean {
    if (it.action != Intent.ACTION_VIEW) return false
    if (it.type == "time/epoch") return true
    if (it.type != null) return false
    val data = it.data ?: return false
    if (data.lastPathSegment?.toLongOrNull() == null) return false
    return data.authority == "com.android.calendar" || data.path?.contains("time") == true
}


/** Extracts the jump-to date from a time-epoch VIEW intent, or null. */
internal fun resolveDateJumpTarget(activityIntent: Intent?): LocalDate? {
    if (activityIntent == null) return null
    if (!isTimeEpochIntent(activityIntent)) return null
    return activityIntent.data?.lastPathSegment?.toLongOrNull()?.toJumpDate()
}

internal fun Long.toJumpDate(): LocalDate? {
    return runCatching {
        Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault()).date
    }.getOrNull()
}

/**
 * Resolves a pre-unlock notification tap ([ReminderFallback]) against the
 * calendar provider. Null when the event is gone or the provider is still
 * unreadable — the caller then leaves the user on home (old behavior).
 */
internal fun resolveReminderFallback(context: Context, fb: ReminderFallback): Instance? {
    return Instance.findForReminder(context, fb.eventId, fb.instanceStart, fb.instanceEnd)
}

/**
 * Resolves which route the app opens on: an ICS import, a pending deep-link, an
 * instance extra (widget or notification tap), a lightweight reminder fallback,
 * or an ACTION_INSERT — else the calendar home (null).
 * Exported activity: malformed extras fall back to no route instead of crashing.
 */
internal fun resolveInitialRoute(
    activityIntent: Intent?,
    uris: List<String>,
    pending: Route?,
    fallback: ReminderFallback? = null,
): Route? {
    return when {
        uris.isNotEmpty() -> Route.Settings.ImportIcs(uris)
        pending != null -> pending
        // Pre-unlock notification tap stashed before composition: resolved
        // against the provider by the LaunchedEffect in CalendarAppContent.
        // Null here is temporary until that resolution lands.
        fallback != null -> null
        activityIntent == null -> null
        else -> routeFromIntent(activityIntent)
    }
}

/** Route carried directly by [activityIntent]: an instance deep-link or an ACTION_INSERT, else null. */
internal fun routeFromIntent(activityIntent: Intent): Route? {
    activityIntent.getStringExtra(ReminderScheduler.EXTRA_INSTANCE)?.let { raw ->
        runCatching { Json.decodeFromString<Instance>(raw) }.getOrNull()
    }?.let { return Route.Event(it) }
    if (activityIntent.action == Intent.ACTION_INSERT &&
        (activityIntent.type == "vnd.android.cursor.dir/event" || activityIntent.type == null)
    ) {
        return editEventFromIntent(activityIntent)
    }
    return null
}
