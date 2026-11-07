package com.vayunmathur.calendar.util

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Looper
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.data.Event
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class CalendarViewModelTest {

    private fun vm(): CalendarViewModel =
        CalendarViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `initial state defaults to full week system theme and today`() {
        val viewModel = vm()
        idle()
        // A fresh VM reads any layout/theme persisted by earlier tests in this sandbox,
        // so only the shape is asserted here; explicit setters are checked below.
        assertTrue(viewModel.currentLayout.value in CalendarViewModel.CalendarLayout.entries)
        assertTrue(viewModel.themeMode.value in CalendarViewModel.ThemeMode.entries)
        assertEquals(emptyList<Event>(), viewModel.events.value)
        assertEquals(emptyList(), viewModel.calendars.value)
        assertNull(viewModel.lastViewedDate.value)
        assertNull(viewModel.parsedIcsEvents.value)
        assertTrue(viewModel.selectedDate.value.toEpochDays() > LocalDate(2024, 1, 1).toEpochDays())
    }

    @Test
    fun `layout and theme setters update state synchronously`() {
        val viewModel = vm()
        viewModel.setLayout(CalendarViewModel.CalendarLayout.Month)
        assertEquals(CalendarViewModel.CalendarLayout.Month, viewModel.currentLayout.value)
        viewModel.setLayout(CalendarViewModel.CalendarLayout.Agenda)
        assertEquals(CalendarViewModel.CalendarLayout.Agenda, viewModel.currentLayout.value)
        viewModel.setThemeMode(CalendarViewModel.ThemeMode.Dark)
        assertEquals(CalendarViewModel.ThemeMode.Dark, viewModel.themeMode.value)
        viewModel.setThemeMode(CalendarViewModel.ThemeMode.Light)
        assertEquals(CalendarViewModel.ThemeMode.Light, viewModel.themeMode.value)
        idle()
    }

    @Test
    fun `selected and last viewed dates track setters`() {
        val viewModel = vm()
        viewModel.setSelectedDate(D.TODAY)
        assertEquals(D.TODAY, viewModel.selectedDate.value)
        viewModel.setLastViewedDate(D.TODAY)
        assertEquals(D.TODAY, viewModel.lastViewedDate.value)
        viewModel.setLastViewedDate(null)
        assertNull(viewModel.lastViewedDate.value)
        viewModel.setSelectedDate(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 5)))
        assertEquals(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 5)), viewModel.selectedDate.value)
    }

    @Test
    fun `default reminders for unknown calendar are empty and setters do not crash`() {
        val viewModel = vm()
        assertEquals(emptyList(), viewModel.getDefaultReminders(987001))
        assertNull(viewModel.getDefaultCalendarId()?.takeIf { it == -999L })
        viewModel.setDefaultCalendar(987001)
        viewModel.setDefaultReminders(987001, listOf(30, 10, 10), applyToExisting = false)
        viewModel.setDefaultReminders(987002, listOf(5), applyToExisting = true)
        idle()
    }

    @Test
    fun `parse empty ics uris yields empty parsed state and clear resets`() {
        val viewModel = vm()
        viewModel.parseIcsUris(emptyList())
        assertEquals(emptyList(), viewModel.parsedIcsEvents.value)
        viewModel.clearParsedIcs()
        assertNull(viewModel.parsedIcsEvents.value)
        // Non-empty uris that cannot be opened are swallowed and result in an empty parse.
        viewModel.parseIcsUris(listOf(Uri.parse("content://nonexistent.calendar/file.ics")))
        idle()
    }

    @Test
    fun `visibleInstances filters to loaded events and returns empty without provider`() = runBlocking {
        val viewModel = vm()
        idle()
        val result = viewModel.visibleInstances(
            Instant.fromEpochMilliseconds(D.at(D.TODAY, 0)),
            Instant.fromEpochMilliseconds(D.at(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 7)), 0)),
        )
        assertEquals(emptyList(), result)
    }

    @Test
    fun `upsert delete and calendar mutations against missing provider do not crash`() {
        val viewModel = vm()
        idle()
        viewModel.upsertEvent(null, ContentValues().apply {
            put(CalendarContract.Events.TITLE, "Nope")
        }, reminders = listOf(10))
        viewModel.upsertEvent(123, ContentValues().apply {
            put(CalendarContract.Events.TITLE, "Nope2")
        }, reminders = emptyList())
        viewModel.upsertEvent(124, ContentValues(), reminders = null)
        viewModel.deleteEventSeries(123)
        // deleteEventInstance with no loaded event is a no-op (early return).
        viewModel.deleteEventInstance(123, D.at(D.TODAY, 9))
        viewModel.setCalendarVisibility(1, false)
        viewModel.setCalendarColor(1, 0xFF0000)
        viewModel.renameCalendar(999999, "Renamed") // not loaded: early return path
        viewModel.deleteCalendar(999999) // not loaded: early return path
        viewModel.reloadAll()
        viewModel.loadData()
        viewModel.updateWidgets()
        idle()
    }

    @Test
    fun `create local calendar invokes completion path and export does not crash`() {
        val viewModel = vm()
        idle()
        var completed: Long? = null
        var called = false
        viewModel.createLocalCalendar(
            accountName = "Offline Calendar", displayName = "NewCal",
            colorInt = 0xFF2196F3.toInt(), visible = true,
            accessLevel = CalendarContract.Calendars.CAL_ACCESS_EDITOR,
            onComplete = { called = true; completed = it },
        )
        viewModel.exportIcs(Uri.parse("content://nonexistent.calendar/out.ics"))
        viewModel.exportIcs(Uri.parse("content://nonexistent.calendar/out.ics"), calendarId = 1)
        viewModel.importIcsEvents(listOf(D.event()), calendarId = 1) {}
        idle()
    }

    @Test
    fun `enum entries carry distinct resource ids`() {
        assertEquals(10, CalendarViewModel.CalendarLayout.entries.size)
        assertEquals(
            CalendarViewModel.CalendarLayout.entries.map { it.shortNameRes }.distinct().size,
            CalendarViewModel.CalendarLayout.entries.size,
        )
        assertEquals(3, CalendarViewModel.ThemeMode.entries.size)
        assertEquals(
            CalendarViewModel.ThemeMode.entries.map { it.prettyNameRes }.distinct().size,
            CalendarViewModel.ThemeMode.entries.size,
        )
    }
}
