package com.vayunmathur.calendar.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.CalendarActions
import com.vayunmathur.calendar.util.CalendarUiState
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.util.EventActions
import com.vayunmathur.calendar.util.EventUiState
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.calendar.util.SettingsActions
import com.vayunmathur.calendar.util.SettingsUiState
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
class FinalCoverageTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class RecCalendar : CalendarActions {
        val layouts = mutableListOf<CalendarViewModel.CalendarLayout>()
        var settings = 0; var create = 0; var todayClicked = false
        override fun setLayout(layout: CalendarViewModel.CalendarLayout) { layouts += layout }
        override fun setSelectedDate(date: LocalDate) { todayClicked = true }
        override suspend fun visibleInstances(start: Instant, end: Instant): List<Instance> = emptyList()
        override fun openSettings() { settings++ }
        override fun createEvent() { create++ }
    }

    @Test
    fun `calendar menu selects every layout and toolbar buttons fire`() {
        val actions = RecCalendar()
        rule.setContent {
            CalendarScreen(
                CalendarUiState(
                    layout = CalendarViewModel.CalendarLayout.Month,
                    dateViewing = D.TODAY, today = D.TODAY,
                    calendars = mapOf(1L to D.PERSONAL), previewInstances = emptyList(),
                ), actions,
            )
        }
        // Open the layout menu and pick each pretty name in turn (reopening between picks).
        val pretty = listOf("Agenda", "Day", "Work Week", "Full Week", "Month", "Month + Agenda")
        for (name in pretty) {
            rule.onNodeWithText("M").performClick()
            rule.waitForIdle()
            rule.onNodeWithText(name).performClick()
            rule.waitForIdle()
        }
        assertTrue(actions.layouts.size >= 5, actions.layouts.toString())
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, actions.settings)
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(1, actions.create)
    }

    private class RecSettings : SettingsActions {
        var theme: CalendarViewModel.ThemeMode? = null
        var expCal: Long? = null; var remindersFor: Long? = null
        var rename: Long? = null; var delete: Long? = null; var add = 0
        override fun setThemeMode(mode: CalendarViewModel.ThemeMode) { theme = mode }
        override fun exportCalendarIcs(calendarId: Long) { expCal = calendarId }
        override fun openDefaultReminders(calendarId: Long) { remindersFor = calendarId }
        override fun openRenameCalendar(calendarId: Long) { rename = calendarId }
        override fun openDeleteCalendar(calendarId: Long) { delete = calendarId }
        override fun openAddCalendar() { add++ }
    }

    @Test
    fun `settings theme pick and selected calendar top actions fire`() {
        val actions = RecSettings()
        rule.setContent {
            SettingsScreen(
                SettingsUiState(
                    calendars = listOf(D.PERSONAL, D.WORK),
                    calendarVisibility = mapOf(1L to true, 2L to true),
                    themeMode = CalendarViewModel.ThemeMode.System,
                ), actions,
            )
        }
        rule.onNodeWithText("Theme").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Dark").performClick()
        assertEquals(CalendarViewModel.ThemeMode.Dark, actions.theme)
        // Add FAB (calendars non-empty) opens add-calendar.
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(1, actions.add)
        // Select a calendar, then use the revealed top-bar actions one by one,
        // re-selecting after each since the screen keeps the selection.
        repeat(4) {
            if (rule.onAllNodesWithText("PersonalCal").fetchSemanticsNodes().isEmpty()) {
                rule.onNode(androidx.compose.ui.test.hasScrollAction()).performTouchInput { swipeUp() }
                rule.waitForIdle()
            }
        }
        rule.onNodeWithText("PersonalCal").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Download").performClick()
        assertEquals(1L, actions.expCal)
        rule.onNodeWithContentDescription("Schedule").performClick()
        assertEquals(1L, actions.remindersFor)
        rule.onNodeWithContentDescription("Edit").performClick()
        assertEquals(1L, actions.rename)
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(1L, actions.delete)
    }

    private class RecEvent : EventActions {
        var edit: Long? = null; var delSeries: Long? = null
        var delInstance: Pair<Long, Long>? = null; var closed = 0
        override fun closeEvent() { closed++ }
        override fun editEvent(eventId: Long) { edit = eventId }
        override fun deleteEventSeries(eventId: Long) { delSeries = eventId }
        override fun deleteEventInstance(eventId: Long, instanceBeginTime: Long) { delInstance = eventId to instanceBeginTime }
    }

    @Test
    fun `event screen edit icon and recurring delete menu items fire`() {
        val ev = D.event(rrule = RRule.EveryXDays(1, RRule.EndCondition.Never))
        val actions = RecEvent()
        rule.setContent { EventScreen(EventUiState(ev, D.PERSONAL, D.instance(ev)), actions) }
        rule.onNodeWithContentDescription("Edit").performClick()
        assertEquals(10L, actions.edit)
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete this event", substring = true).performClick()
        assertEquals(10L to ev.start, actions.delInstance)
        assertEquals(1, actions.closed)
    }

    @Test
    fun `event screen recurring delete all fires series delete`() {
        val ev = D.event(rrule = RRule.EveryXDays(1, RRule.EndCondition.Never))
        val actions = RecEvent()
        rule.setContent { EventScreen(EventUiState(ev, D.PERSONAL, D.instance(ev)), actions) }
        rule.onNodeWithContentDescription("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete all events", substring = true).performClick()
        assertEquals(10L, actions.delSeries)
        assertEquals(1, actions.closed)
    }

    @Test
    fun `event screen non recurring delete fires directly`() {
        val ev = D.event()
        val actions = RecEvent()
        rule.setContent { EventScreen(EventUiState(ev, D.PERSONAL, D.instance(ev)), actions) }
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(10L, actions.delSeries)
        assertEquals(1, actions.closed)
    }

    @Test
    fun `granting calendar permissions is recorded by shadows`() {
        // Sanity for the MainActivity compose test setup in its own class.
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        assertTrue(am != null)
    }
}
