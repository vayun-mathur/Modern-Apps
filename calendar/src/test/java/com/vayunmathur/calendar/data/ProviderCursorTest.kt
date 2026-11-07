package com.vayunmathur.calendar.data

import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.FakeCalendarData
import com.vayunmathur.calendar.util.HolidayCalendarManager
import com.vayunmathur.calendar.util.RRule
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class ProviderCursorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeCalendarData.reset()
        FakeCalendarData.register(context)
    }

    private fun seedBasic() {
        val standup = D.event(
            title = "CursorStandup", location = "RoomC", description = "DescC",
            reminders = listOf(30, 10),
            rrule = RRule.EveryXDays(1, RRule.EndCondition.Count(4)),
            exdate = listOf(LocalDate(2026, 3, 11)),
            rdate = listOf(LocalDate(2026, 3, 16)),
        )
        val deleted = D.event(id = 11, title = "DeletedOne")
        val untitled = D.event(id = 12, title = "WillBeNull")
        val durationOnly = D.event(id = 13, title = "DurationOnly")
        FakeCalendarData.calendars = listOf(
            FakeCalendarData.calendarRow(D.PERSONAL),
            FakeCalendarData.calendarRow(D.WORK),
            FakeCalendarData.calendarRow(D.READONLY),
        )
        FakeCalendarData.events = listOf(
            FakeCalendarData.eventRow(standup),
            FakeCalendarData.eventRow(deleted, deleted = 1),
            // Title column null -> row skipped.
            FakeCalendarData.eventRow(untitled) + (CalendarContract.Events.TITLE to null),
            // DTEND 0 + DURATION fallback.
            FakeCalendarData.eventRow(durationOnly, duration = "PT2H", endOverride = 0L),
            // DTEND 0 + unparseable duration -> end stays 0.
            FakeCalendarData.eventRow(D.event(id = 14, title = "BadDuration"), duration = "not-a-duration", endOverride = 0L),
        )
        FakeCalendarData.reminders = listOf(
            mapOf(CalendarContract.Reminders.EVENT_ID to 10L, CalendarContract.Reminders.MINUTES to 30),
            mapOf(CalendarContract.Reminders.EVENT_ID to 10L, CalendarContract.Reminders.MINUTES to -5),
            mapOf(CalendarContract.Reminders.EVENT_ID to 10L, CalendarContract.Reminders.MINUTES to 10),
        )
        FakeCalendarData.instances = listOf(
            FakeCalendarData.instanceRow(D.instance(standup)),
            FakeCalendarData.instanceRow(D.instance(id = 301, eventID = 999, title = "OrphanInstance")),
        )
    }

    @Test
    fun `getAllCalendars parses rows`() {
        seedBasic()
        val calendars = Calendar.getAllCalendars(context)
        assertEquals(3, calendars.size)
        assertEquals(D.PERSONAL, calendars[0])
        assertEquals(D.WORK, calendars[1])
        assertEquals(D.READONLY, calendars[2])
    }

    @Test
    fun `getAllEvents parses rows skips deleted and untitled and resolves duration`() {
        seedBasic()
        val events = Event.getAllEvents(context)
        assertEquals(listOf("CursorStandup", "DurationOnly", "BadDuration"), events.map { it.title })
        val standup = events[0]
        assertEquals(1L, standup.calendarID)
        assertEquals("RoomC", standup.location)
        // Negative reminder minutes clamp to 0; list is sorted on read.
        assertEquals(listOf(0, 10, 30), standup.reminders)
        assertNotNull(standup.rrule)
        assertEquals(listOf(LocalDate(2026, 3, 11)), standup.exdate)
        assertEquals(listOf(LocalDate(2026, 3, 16)), standup.rdate)
        val durationOnly = events[1]
        assertEquals(durationOnly.start + 2 * 3_600_000L, durationOnly.end)
        val bad = events[2]
        assertEquals(bad.start, bad.end) // unparseable duration falls back to start + 0
    }

    @Test
    fun `getInstances parses rows and findForReminder matches exact then any`() {
        seedBasic()
        val start = Instant.fromEpochMilliseconds(D.at(D.TODAY, 0))
        val end = Instant.fromEpochMilliseconds(D.at(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 7)), 0))
        val instances = Instance.getInstances(context, start, end)
        assertEquals(2, instances.size)
        assertEquals("CursorStandup", instances[0].eventTitle)
        val exact = Instance.findForReminder(context, 10, D.at(D.TODAY, 9), D.at(D.TODAY, 10))
        assertNotNull(exact)
        assertEquals(10L, exact.eventID)
        // No exact begin: falls back to any instance of the event in the window.
        val any = Instance.findForReminder(context, 10, D.at(D.TODAY, 9) + 60_000L, D.at(D.TODAY, 10))
        assertNotNull(any)
        assertNull(Instance.findForReminder(context, 424242, D.at(D.TODAY, 9), D.at(D.TODAY, 10)))
    }

    @Test
    fun `getVisibleInstances filters orphan and hidden calendar instances`() {
        seedBasic()
        // Add an instance whose event is in the hidden (visible=false) calendar.
        val hiddenEvent = D.event(id = 20, calendar = D.READONLY, title = "HiddenEvent")
        FakeCalendarData.events = FakeCalendarData.events + FakeCalendarData.eventRow(hiddenEvent)
        FakeCalendarData.instances = FakeCalendarData.instances +
            FakeCalendarData.instanceRow(D.instance(hiddenEvent, id = 302))
        val start = Instant.fromEpochMilliseconds(D.at(D.TODAY, 0))
        val end = Instant.fromEpochMilliseconds(D.at(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 7)), 0))
        val visible = Instance.getVisibleInstances(context, start, end)
        assertEquals(listOf("CursorStandup"), visible.map { it.eventTitle })
    }

    @Test
    fun `holiday manager reads added calendars from provider rows`() {
        FakeCalendarData.calendars = listOf(
            FakeCalendarData.calendarRow(D.PERSONAL.copy(id = 50, accountName = "Holidays"), name = "holiday::US::en"),
            FakeCalendarData.calendarRow(D.PERSONAL.copy(id = 51, accountName = "Holidays"), name = "holiday::FR"),
            FakeCalendarData.calendarRow(D.PERSONAL.copy(id = 52, accountName = "Other"), name = "regular"),
        )
        val added = HolidayCalendarManager.addedCalendars(context)
        assertTrue("US" to "en" in added, added.toString())
        assertTrue("FR" to "en" in added, added.toString())
        assertEquals(setOf("US", "FR"), HolidayCalendarManager.addedCountryCodes(context))
    }

    @Test
    fun `holiday manager addCountry inserts when absent and is no-op when present`() {
        FakeCalendarData.calendars = listOf(
            FakeCalendarData.calendarRow(D.PERSONAL.copy(id = 50, accountName = "Holidays"), name = "holiday::US::en"),
        )
        // Already present (calendarIdFor finds it): returns without inserting new id consumption.
        val before = FakeCalendarData.nextId
        HolidayCalendarManager.addCountry(context, "US", "United States", "en", "English")
        assertEquals(before, FakeCalendarData.nextId)
        // Absent: inserts a calendar (consumes an id) and bulk-inserts any holiday rows.
        HolidayCalendarManager.addCountry(context, "DE", "Germany", "de", "German")
        assertTrue(FakeCalendarData.nextId > before)
        HolidayCalendarManager.removeCountry(context, "US", "en")
        HolidayCalendarManager.removeCountry(context, "US")
    }

    @Test
    fun `all day event row resolves timezone in utc`() {
        val allDay = D.event(id = 30, allDay = true, title = "AllDayRowEvent")
        FakeCalendarData.events = listOf(FakeCalendarData.eventRow(allDay))
        val events = Event.getAllEvents(context)
        assertEquals(1, events.size)
        assertTrue(events[0].allDay)
        assertEquals(D.TODAY, events[0].startDateTimeDisplay.date)
    }
}
