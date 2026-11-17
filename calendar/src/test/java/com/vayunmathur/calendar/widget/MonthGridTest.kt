package com.vayunmathur.calendar.widget

import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.widget.glance.cappedForCell
import com.vayunmathur.calendar.widget.glance.computeMonthGrid
import com.vayunmathur.calendar.widget.glance.contrastingTextOn
import com.vayunmathur.calendar.widget.glance.maxChipsForSize
import com.vayunmathur.calendar.widget.glance.sortedForMonthCell
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonthGridTest {

    @Test
    fun gridStartsOnLocaleWeekStartAndHasSevenDayWeeks() {
        // July 2026: the 1st is a Wednesday (ISO 3). With a Monday week-start (ISO 1),
        // the grid starts Monday June 29; with a Sunday start (ISO 7), Sunday June 28.
        val mondayGrid = computeMonthGrid(2026, Month.JULY, firstDayOfWeek = 1)
        assertEquals(LocalDate(2026, 6, 29), mondayGrid.first().first())
        assertTrue(mondayGrid.isNotEmpty())
        mondayGrid.forEach { week ->
            assertEquals(7, week.size)
            assertEquals(1, week.first().dayOfWeek.isoDayNumber)
        }

        val sundayGrid = computeMonthGrid(2026, Month.JULY, firstDayOfWeek = 7)
        assertEquals(LocalDate(2026, 6, 28), sundayGrid.first().first())
        sundayGrid.forEach { week ->
            assertEquals(7, week.size)
            assertEquals(7, week.first().dayOfWeek.isoDayNumber)
        }
    }

    @Test
    fun gridCoversFullWeeksAndContainsAllMonthDays() {
        val grid = computeMonthGrid(2026, Month.JULY, firstDayOfWeek = 1)
        val days = grid.flatten()

        // Grid is a run of consecutive days covering whole weeks.
        val sorted = days.sorted()
        assertEquals(days.size, sorted.size)
        assertTrue(days.zipWithNext().all { (a, b) -> b.toEpochDays() - a.toEpochDays() == 1L })

        // Every day of July is present exactly once.
        val julyDays = (1..31).map { LocalDate(2026, Month.JULY, it) }
        assertTrue(julyDays.all { it in days })
        assertEquals(days.size, days.toSet().size)

        // Out-of-month padding days come from June and August.
        assertTrue(days.any { it.month == Month.JUNE })
        assertTrue(days.any { it.month == Month.AUGUST })
    }

    @Test
    fun multiDaySpanLandsOnEveryCoveredDate() {
        val begin = LocalDate(2026, 7, 10).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val end = LocalDate(2026, 7, 13).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val instance = Instance(
            id = 1L,
            eventID = 1L,
            begin = begin,
            end = end,
            timezone = "UTC",
            allDay = true,
            eventTitle = "Trip",
            color = 0,
            rrule = null,
        )
        val covered = listOf(
            LocalDate(2026, 7, 10),
            LocalDate(2026, 7, 11),
            LocalDate(2026, 7, 12),
        )
        val grid = computeMonthGrid(2026, Month.JULY, firstDayOfWeek = 1)
        val eventsByDay = grid.flatten().associateWith { day ->
            listOf(instance).filter { day in it.spanDays }
        }
        covered.forEach { day ->
            assertTrue(eventsByDay.getValue(day).contains(instance), "expected event on $day")
        }
        // Exclusive end (midnight July 13) does not spill onto the 13th.
        assertTrue(eventsByDay.getValue(LocalDate(2026, 7, 13)).isEmpty())
        assertTrue(eventsByDay.getValue(LocalDate(2026, 7, 9)).isEmpty())
    }

    @Test
    fun sortedForMonthCellOrdersAllDayThenEarliestFirst() {
        val zone = TimeZone.UTC
        fun instance(id: Long, day: LocalDate, hour: Int, minute: Int = 0, allDay: Boolean = false): Instance {
            val begin = LocalDateTime(day, LocalTime(hour, minute)).toInstant(zone).toEpochMilliseconds()
            return Instance(
                id = id,
                eventID = id,
                begin = begin,
                end = begin + 60 * 60 * 1000,
                timezone = "UTC",
                allDay = allDay,
                eventTitle = "Event $id",
                color = 0,
                rrule = null,
            )
        }
        val day = LocalDate(2026, 7, 10)
        val evening = instance(1, day, 18)
        val morning = instance(2, day, 9)
        val allDay = instance(3, day, 0, allDay = true)
        // All-day starts at midnight so it sorts first, matching the in-app month view.
        assertEquals(listOf(allDay, morning, evening), listOf(evening, allDay, morning).sortedForMonthCell())
    }

    @Test
    fun cappedForCellCountsOverflow() {
        val (visible, hidden) = listOf(1, 2, 3, 4, 5).cappedForCell(3)
        assertEquals(listOf(1, 2, 3), visible)
        assertEquals(2, hidden)

        val (all, none) = listOf(1, 2).cappedForCell(3)
        assertEquals(listOf(1, 2), all)
        assertEquals(0, none)
    }

    @Test
    fun maxChipsGrowsWithHeightAndStaysInRange() {
        val weeks = 5
        val small = maxChipsForSize(200.dp, weeks)
        val large = maxChipsForSize(800.dp, weeks)
        assertTrue(small in 1..4)
        assertTrue(large in 1..4)
        assertTrue(large >= small)
        // Degenerate sizes fall back to the default instead of crashing.
        assertEquals(3, maxChipsForSize(0.dp, weeks))
        assertEquals(3, maxChipsForSize(400.dp, 0))
    }

    @Test
    fun contrastingTextIsReadableOnLightAndDarkBackgrounds() {
        assertEquals(Color.Black, contrastingTextOn(Color.White))
        assertEquals(Color.White, contrastingTextOn(Color.Black))
    }
}
