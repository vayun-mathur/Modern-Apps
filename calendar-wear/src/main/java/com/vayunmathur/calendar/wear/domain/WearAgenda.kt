package com.vayunmathur.calendar.wear.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Events split for the watch's two list sections. */
data class WearAgenda(
    val today: List<WearCalendarEvent>,
    val upcoming: List<WearCalendarEvent>,
)

/**
 * Splits [snapshot] into today's agenda and everything after it, in the
 * watch's own time zone. Events already over are dropped (a multi-day event
 * still running counts as today's). Pure so the section logic is unit-tested
 * without a device.
 */
fun wearAgenda(snapshot: WearCalendarSnapshot, nowMillis: Long, zone: TimeZone): WearAgenda {
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val live = snapshot.events.filter { it.endMillis > nowMillis }
    fun startDate(event: WearCalendarEvent): LocalDate =
        Instant.fromEpochMilliseconds(event.startMillis)
            .toLocalDateTime(if (event.allDay) TimeZone.UTC else zone).date
    return WearAgenda(
        today = live.filter { startDate(it) <= today },
        upcoming = live.filter { startDate(it) > today },
    )
}

/** Day label for grouping upcoming events, e.g. the date an event starts on. */
fun wearEventDay(event: WearCalendarEvent, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(event.startMillis)
        .toLocalDateTime(if (event.allDay) TimeZone.UTC else zone).date
