package com.vayunmathur.calendar.ui

import com.vayunmathur.calendar.data.Instance
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaWindowTest {

    private fun midnightUtc(date: String): Long =
        LocalDate.parse(date).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

    private fun allDayInstance(id: Long, beginDate: String, endDateExclusive: String) = Instance(
        id = id,
        eventID = id,
        begin = midnightUtc(beginDate),
        end = midnightUtc(endDateExclusive),
        timezone = "UTC",
        allDay = true,
        eventTitle = "Test $id",
        color = 0,
        rrule = null,
    )

    @Test
    fun `window widens visible span by prefetch and quantizes to whole pages`() {
        val window = agendaWindowIndices(firstVisible = 50_000, lastVisible = 50_006)
        // Start of the 31-day page containing (50000 - 10), end of the page
        // containing (50006 + 10): scrolling inside the page must not move it.
        assertEquals(49_972, window.first)
        assertEquals(50_033, window.last)
        assertEquals(window, agendaWindowIndices(firstVisible = 50_001, lastVisible = 50_006))
        assertEquals(window, agendaWindowIndices(firstVisible = 50_010, lastVisible = 50_016))
        assertEquals(window, agendaWindowIndices(firstVisible = 49_990, lastVisible = 49_995))
    }

    @Test
    fun `window clamps to list bounds`() {
        val atStart = agendaWindowIndices(firstVisible = 0, lastVisible = 3)
        assertEquals(0, atStart.first)
        val atEnd = agendaWindowIndices(
            firstVisible = AGENDA_TOTAL_DAYS - 3,
            lastVisible = AGENDA_TOTAL_DAYS - 1,
        )
        assertEquals(AGENDA_TOTAL_DAYS - 1, atEnd.last)
        assertTrue(atEnd.first <= atEnd.last)
    }

    @Test
    fun `window covers at least one full page`() {
        val window = agendaWindowIndices(firstVisible = 123, lastVisible = 123)
        assertTrue(window.last - window.first + 1 >= AGENDA_PAGE_DAYS)
    }

    @Test
    fun `grouping files a multi-day instance under each span day in the window`() {
        val instance = allDayInstance(1L, "2026-07-15", "2026-07-18") // spans 15, 16, 17
        val byDay = groupInstancesByDay(
            listOf(instance),
            windowStart = LocalDate.parse("2026-07-16"),
            windowEndExclusive = LocalDate.parse("2026-07-17"),
        )
        // Days outside the window are clipped even though the instance spans them.
        assertEquals(setOf(LocalDate.parse("2026-07-16")), byDay.keys)
        assertEquals(listOf(instance), byDay[LocalDate.parse("2026-07-16")])
    }

    @Test
    fun `grouping sorts each day by start and omits empty days`() {
        val later = allDayInstance(2L, "2026-07-16", "2026-07-17")
        val earlier = allDayInstance(1L, "2026-07-15", "2026-07-17") // spans 15 + 16
        val byDay = groupInstancesByDay(
            listOf(later, earlier),
            windowStart = LocalDate.parse("2026-07-15"),
            windowEndExclusive = LocalDate.parse("2026-07-18"),
        )
        assertEquals(listOf(earlier), byDay[LocalDate.parse("2026-07-15")])
        assertEquals(listOf(earlier, later), byDay[LocalDate.parse("2026-07-16")])
        assertEquals(null, byDay[LocalDate.parse("2026-07-17")])
    }

    @Test
    fun `grouping of no instances is empty`() {
        val byDay = groupInstancesByDay(
            emptyList(),
            windowStart = LocalDate.parse("2026-07-15"),
            windowEndExclusive = LocalDate.parse("2026-07-16"),
        )
        assertTrue(byDay.isEmpty())
    }
}
