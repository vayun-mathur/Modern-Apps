package com.vayunmathur.calendar.wear.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.TimeZone

class WearAgendaTest {

    private fun event(id: Long, start: Long, end: Long) = WearCalendarEvent(
        eventId = id,
        title = "Event $id",
        startMillis = start,
        endMillis = end,
        timezone = "UTC",
    )

    @Test
    fun splitsTodayUpcomingAndDropsFinished() {
        // 2026-11-24T12:00:00Z
        val now = 1_795_521_600_000L
        val hour = 3_600_000L
        val day = 24 * hour
        val snapshot = WearCalendarSnapshot(
            events = listOf(
                event(1L, now - hour, now + hour), // running today
                event(2L, now + hour, now + 2 * hour), // later today
                event(3L, now + day, now + day + hour), // tomorrow
                event(4L, now - 2 * hour, now - hour), // finished
            ),
        )
        val agenda = wearAgenda(snapshot, now, TimeZone.UTC)
        assertEquals(listOf(1L, 2L), agenda.today.map { it.eventId })
        assertEquals(listOf(3L), agenda.upcoming.map { it.eventId })
    }

    @Test
    fun emptySnapshotHasEmptyAgenda() {
        val agenda = wearAgenda(WearCalendarSnapshot(), 0L, TimeZone.UTC)
        assertEquals(emptyList(), agenda.today)
        assertEquals(emptyList(), agenda.upcoming)
    }
}
