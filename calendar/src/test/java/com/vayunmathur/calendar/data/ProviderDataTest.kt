package com.vayunmathur.calendar.data

import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.calendar.util.writeReminders
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class ProviderDataTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // --- Calendar.canModify ---------------------------------------------------

    @Test
    fun `canModify follows access level threshold`() {
        assertTrue(D.PERSONAL.canModify)
        assertTrue(D.WORK.canModify)
        assertTrue(D.NO_ACCOUNT.canModify)
        assertFalse(D.READONLY.canModify)
        assertFalse(Calendar(9, "a", "x", 0, CalendarContract.Calendars.CAL_ACCESS_NONE, true).canModify)
        assertTrue(Calendar(9, "a", "x", 0, CalendarContract.Calendars.CAL_ACCESS_EDITOR, true).canModify)
        assertTrue(Calendar(9, "a", "x", 0, CalendarContract.Calendars.CAL_ACCESS_OWNER, true).canModify)
    }

    // --- Provider reads with no provider rows (Robolectric) ---------------------

    @Test
    fun `getAllCalendars and getAllEvents return empty without provider`() {
        assertEquals(emptyList(), Calendar.getAllCalendars(context))
        assertEquals(emptyList(), Event.getAllEvents(context))
    }

    @Test
    fun `getInstances and visible instances return empty without provider`() {
        val start = Instant.fromEpochMilliseconds(D.at(D.TODAY, 0))
        val end = Instant.fromEpochMilliseconds(D.at(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 7)), 0))
        assertEquals(emptyList(), Instance.getInstances(context, start, end))
        assertEquals(emptyList(), Instance.getVisibleInstances(context, start, end))
        assertNull(Instance.findForReminder(context, 10, D.at(D.TODAY, 9), D.at(D.TODAY, 10)))
    }

    // --- Event.toContentValues ------------------------------------------------------

    @Test
    fun `timed non recurring event writes dtstart dtend and no rrule`() {
        val ev = D.event(title = "TimedCV", location = "RoomCV", description = "DescCV")
        val values = ev.toContentValues(7)
        assertEquals("TimedCV", values.getAsString(CalendarContract.Events.TITLE))
        assertEquals("DescCV", values.getAsString(CalendarContract.Events.DESCRIPTION))
        assertEquals("RoomCV", values.getAsString(CalendarContract.Events.EVENT_LOCATION))
        assertEquals(7L, values.getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertEquals(ev.start, values.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(ev.end, values.getAsLong(CalendarContract.Events.DTEND))
        assertEquals(0, values.getAsInteger(CalendarContract.Events.ALL_DAY))
        assertEquals("UTC", values.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertNull(values.getAsString(CalendarContract.Events.RRULE))
        assertNull(values.getAsString(CalendarContract.Events.RDATE))
    }

    @Test
    fun `recurring event writes duration and rrule instead of dtend`() {
        val ev = D.event(
            rrule = RRule.EveryXDays(2, RRule.EndCondition.Count(5)),
            exdate = listOf(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 2))),
            reminders = listOf(10),
        )
        val values = ev.toContentValues(1)
        assertNull(values.getAsLong(CalendarContract.Events.DTEND))
        assertNotNull(values.getAsString(CalendarContract.Events.DURATION))
        val rrule = values.getAsString(CalendarContract.Events.RRULE)
        assertTrue(rrule.contains("FREQ=DAILY"), rrule)
        assertTrue(values.containsKey(CalendarContract.Events.EXDATE))
    }

    @Test
    fun `all day event snaps to midnight utc and writes date exdate`() {
        val ev = D.event(allDay = true, title = "AllDayCV", exdate = listOf(D.TODAY))
        val values = ev.toContentValues(1)
        assertEquals(1, values.getAsInteger(CalendarContract.Events.ALL_DAY))
        assertEquals("UTC", values.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(D.at(D.TODAY, 0), values.getAsLong(CalendarContract.Events.DTSTART))
    }

    @Test
    fun `rdate column value uses dates for all day and datetime for timed`() {
        val dates = listOf(LocalDate(2026, 3, 16), LocalDate(2026, 3, 20))
        val allDay = D.event(allDay = true, rdate = dates)
        assertEquals("20260316,20260320", allDay.rdateColumnValue())
        val timed = D.event(rdate = dates)
        val value = timed.rdateColumnValue()
        assertNotNull(value)
        assertTrue(value.contains("20260316"), value)
        assertTrue(value.contains("20260320"), value)
        assertNull(D.event().rdateColumnValue())
    }

    @Test
    fun `withoutRdate removes only that date and recurrence flags follow rrule and rdate`() {
        val d1 = LocalDate(2026, 3, 16)
        val d2 = LocalDate(2026, 3, 20)
        val ev = D.event(rdate = listOf(d1, d2))
        assertTrue(ev.isRecurring)
        assertEquals(listOf(d2), ev.withoutRdate(d1).rdate)
        assertEquals(listOf(d1, d2), ev.rdate)
        assertFalse(D.event().isRecurring)
        assertTrue(D.event(rrule = RRule.EveryXWeeks(1, listOf(DayOfWeek.MONDAY), RRule.EndCondition.Never)).isRecurring)
    }

    @Test
    fun `display times resolve in utc for all day events`() {
        val ev = D.event(allDay = true)
        assertEquals(D.TODAY, ev.startDateTimeDisplay.date)
        assertEquals(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 1)), ev.endDateTimeDisplay.date)
        val timed = D.event()
        assertNotNull(timed.startDateTimeDisplay)
        assertNotNull(timed.endDateTimeDisplay)
    }

    // --- Instance spanDays --------------------------------------------------------------

    @Test
    fun `instance span days covers single and multi day spans`() {
        val single = D.instance()
        assertEquals(listOf(D.TODAY), single.spanDays)
        val multi = D.instance(
            id = 201, date = D.TODAY, startHour = 9, endHour = 10,
        ).copy(end = D.at(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 2)), 10))
        assertEquals(3, multi.spanDays.size)
        assertEquals(D.TODAY, multi.spanDays.first())
        val midnightEnd = D.instance().copy(end = D.at(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 1)), 0))
        // Ending exactly at midnight covers only the start day.
        assertEquals(listOf(D.TODAY), midnightEnd.spanDays)
        val allDay = D.instance(id = 202, allDay = true)
        assertEquals(listOf(D.TODAY), allDay.spanDays)
    }

    @Test
    fun `instance display times are consistent`() {
        val inst = D.instance()
        assertEquals(inst.startDateTime, inst.startDateTimeDisplay)
        assertEquals(inst.endDateTime, inst.endDateTimeDisplay)
        assertTrue(inst.endDateTime > inst.startDateTime)
    }

    // --- ReminderMirror ----------------------------------------------------------------------

    @Test
    fun `reminder mirror round trips entries and ignores later writes overwrite`() = runBlocking {
        val entries = listOf(
            ReminderMirror.Entry(11, 10, 1000L, 2000L),
            ReminderMirror.Entry(12, 30, 3000L, 4000L),
        )
        ReminderMirror.write(context, entries)
        assertEquals(entries, ReminderMirror.read(context))
        ReminderMirror.write(context, emptyList())
        assertEquals(emptyList(), ReminderMirror.read(context))
        ReminderMirror.write(context, listOf(ReminderMirror.Entry(1, 5, 10L, 20L)))
        assertEquals(listOf(ReminderMirror.Entry(1, 5, 10L, 20L)), ReminderMirror.read(context))
    }

    // --- writeReminders extension ----------------------------------------------------------------

    @Test
    fun `writeReminders against missing provider does not throw`() {
        // Robolectric has no CalendarProvider; the helper must swallow the failure.
        context.contentResolver.writeReminders(42, listOf(10, 10, 30))
        context.contentResolver.writeReminders(42, emptyList())
    }
}
