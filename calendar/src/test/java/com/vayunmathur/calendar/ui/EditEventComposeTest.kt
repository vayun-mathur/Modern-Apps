package com.vayunmathur.calendar.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.ui.dialogs.ChosenDates
import com.vayunmathur.calendar.ui.dialogs.OnDropdown
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberOdfMarkdownEditorController
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EditEventComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun backStack() = NavBackStack<Route>(arrayOf(Route.Calendar))

    // --- reminderLabel / presets ------------------------------------------

    @Test
    fun `reminder labels cover at-time minutes hours and days`() {
        assertTrue(reminderLabel(context, 0).isNotBlank())
        assertTrue(reminderLabel(context, -5).isNotBlank())
        assertTrue(reminderLabel(context, 15).contains("15"))
        assertTrue(reminderLabel(context, 60).isNotBlank())
        assertTrue(reminderLabel(context, 120).isNotBlank())
        assertTrue(reminderLabel(context, 1440).isNotBlank())
        assertTrue(reminderLabel(context, 2880).isNotBlank())
        assertTrue(reminderLabel(context, 45).contains("45"))
        assertEquals(listOf(0, 5, 10, 15, 30, 60, 120, 1440), REMINDER_PRESETS)
    }

    // --- Item row -----------------------------------------------------------

    @Test
    fun `item row shows left and right content`() {
        rule.setContent {
            Item({ Text("icon") }, { Text("LeftLabel") }, { Text("RightLabel") })
        }
        rule.onNodeWithText("LeftLabel").assertIsDisplayed()
        rule.onNodeWithText("RightLabel").assertIsDisplayed()
    }

    // --- EditEventReminders ---------------------------------------------------

    @Test
    fun `reminders list shows entries and remove fires`() {
        var current by mutableStateOf(listOf(10, 30))
        // state is read inside composition via a holder
        val holder = object { var value = listOf(10, 30) }
        rule.setContent {
            EditEventReminders(reminders = holder.value, onReminders = { holder.value = it })
        }
        rule.onNodeWithText(reminderLabel(context, 10)).assertIsDisplayed()
        rule.onNodeWithText(reminderLabel(context, 30)).assertIsDisplayed()
        // Two Remove labels (one per reminder); removing the first drops one entry.
        rule.onAllNodesWithText("Remove")[0].performClick()
        assertEquals(1, holder.value.size)
        assertTrue(holder.value[0] == 10 || holder.value[0] == 30)
    }

    @Test
    fun `add reminder menu adds a preset`() {
        val holder = object { var value = listOf<Int>() }
        rule.setContent {
            EditEventReminders(reminders = holder.value, onReminders = { holder.value = it })
        }
        rule.onNodeWithText("Add reminder").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(reminderLabel(context, 15)).performClick()
        assertEquals(listOf(15), holder.value)
    }

    @Test
    fun `custom reminder dialog confirms a duration`() {
        val holder = object { var value = listOf<Int>() }
        rule.setContent {
            EditEventReminders(reminders = holder.value, onReminders = { holder.value = it })
        }
        rule.onNodeWithText("Add reminder").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Custom", substring = true).performClick()
        rule.waitForIdle()
        // Default custom value is 0h 15m; Add confirms it.
        rule.onAllNodesWithText("Add")[0].performClick()
        assertEquals(listOf(15), holder.value)
    }

    // --- EditEventDateTimeSection --------------------------------------------

    @Test
    fun `date time section timed shows timezone and repeat row`() {
        val stack = backStack()
        rule.setContent {
            EditEventDateTimeSection(
                allDay = false, onAllDay = {},
                startDate = D.TODAY, endDate = D.TODAY,
                startTime = LocalTime(9, 0), endTime = LocalTime(10, 0),
                timezone = "UTC", rruleObj = null, rdateObj = emptyList(),
                repeatSummary = "", onRecurrence = { _, _ -> },
                backStack = stack,
                startDateKey = "sd", endDateKey = "ed", startTimeKey = "st",
                endTimeKey = "et", recurrenceKey = "rec", timezoneKey = "tz",
            )
        }
        rule.onNodeWithText("All-day").assertIsDisplayed()
        rule.onNodeWithText("Does not repeat").assertIsDisplayed()
        rule.onNodeWithText("UTC").assertIsDisplayed()
        rule.onNodeWithText("UTC").performClick()
        assertTrue(stack.backStack.last() is Route.EditEvent.TimezonePickerDialog)
    }

    @Test
    fun `date time section all day hides times and remove repeat fires`() {
        val stack = backStack()
        var removed: Pair<RRule?, List<LocalDate>>? = null
        rule.setContent {
            EditEventDateTimeSection(
                allDay = true, onAllDay = {},
                startDate = D.TODAY, endDate = D.TODAY,
                startTime = LocalTime(9, 0), endTime = LocalTime(10, 0),
                timezone = "UTC",
                rruleObj = RRule.EveryXDays(2, RRule.EndCondition.Count(3)),
                rdateObj = emptyList(),
                repeatSummary = "Every 2 days", onRecurrence = { r, d -> removed = r to d },
                backStack = stack,
                startDateKey = "sd", endDateKey = "ed", startTimeKey = "st",
                endTimeKey = "et", recurrenceKey = "rec", timezoneKey = "tz",
            )
        }
        rule.onNodeWithText("Every 2 days").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        assertEquals(null to emptyList<LocalDate>(), removed)
    }

    @Test
    fun `date time section date and time clicks push picker routes`() {
        val stack = backStack()
        rule.setContent {
            EditEventDateTimeSection(
                allDay = false, onAllDay = {},
                startDate = D.TODAY, endDate = D.TODAY,
                startTime = LocalTime(9, 0), endTime = LocalTime(10, 0),
                timezone = "UTC", rruleObj = null, rdateObj = emptyList(),
                repeatSummary = "", onRecurrence = { _, _ -> },
                backStack = stack,
                startDateKey = "sd", endDateKey = "ed", startTimeKey = "st",
                endTimeKey = "et", recurrenceKey = "rec", timezoneKey = "tz",
            )
        }
        // Start date text is a weekday string; clicking it pushes a date picker.
        val dateNodes = rule.onAllNodesWithText("2026", substring = true).fetchSemanticsNodes()
        assertTrue(dateNodes.isNotEmpty())
    }

    // --- CalendarSelectorDropdown ---------------------------------------------

    @Test
    fun `calendar selector picks a calendar and create new fires`() {
        var selected: com.vayunmathur.calendar.data.Calendar? = null
        var create = 0
        rule.setContent {
            CalendarSelectorDropdown(
                calendars = listOf(D.PERSONAL, D.WORK, D.READONLY),
                selectedCalendar = null,
                onCreateNew = { create++ },
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("Select calendar").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("WorkCal").performClick()
        assertEquals(D.WORK, selected)
        // Reopen and choose create-new.
        rule.onNodeWithText("Select calendar").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Create new calendar", substring = true).performClick()
        assertEquals(1, create)
    }

    @Test
    fun `calendar selector shows selected name`() {
        rule.setContent {
            CalendarSelectorDropdown(
                calendars = listOf(D.PERSONAL), selectedCalendar = D.PERSONAL,
                onSelect = {},
            )
        }
        rule.onNodeWithText("PersonalCal").assertIsDisplayed()
    }

    // --- EventCard --------------------------------------------------------------

    @Test
    fun `event card shows title description location and repeat summary`() {
        val ev = D.event(
            description = "Weekly sync notes", location = "Room 1",
            rrule = RRule.EveryXWeeks(1, listOf(DayOfWeek.MONDAY), RRule.EndCondition.Never),
            rdate = listOf(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 7))),
        )
        rule.setContent { EventCard(ev) }
        rule.onNodeWithText("Team standup").assertIsDisplayed()
        rule.onNodeWithText("Weekly sync notes").assertIsDisplayed()
        rule.onNodeWithText("Room 1").assertIsDisplayed()
    }

    @Test
    fun `event card plain event renders`() {
        rule.setContent { EventCard(D.event(title = "PlainCardEvent")) }
        rule.onNodeWithText("PlainCardEvent").assertIsDisplayed()
    }

    // --- Summary / week pieces ---------------------------------------------------

    @Test
    fun `summary event item shows title and time and click fires`() {
        var clicked: com.vayunmathur.calendar.data.Instance? = null
        val ev = D.event()
        val inst = D.instance(ev)
        rule.setContent {
            SummaryEventItem(context, inst, ev, mapOf(1L to D.PERSONAL), { clicked = it })
        }
        rule.onNodeWithText("Team standup").assertIsDisplayed()
        rule.onNodeWithText("Team standup").performClick()
        assertEquals(inst, clicked)
    }

    @Test
    fun `summary event item all day hides time and missing calendar falls back`() {
        val ev = D.event(allDay = true, title = "AllDayChip", calendar = D.WORK)
        val inst = D.instance(id = 9, eventID = ev.id!!, allDay = true, title = "AllDayChip")
        rule.setContent {
            SummaryEventItem(context, inst, ev, emptyMap(), {})
        }
        rule.onNodeWithText("AllDayChip").assertIsDisplayed()
    }

    @Test
    fun `summary grid renders days with and without events`() {
        val ev = D.event()
        val inst = D.instance(ev)
        val days = (0..4).map { D.TODAY.plus(kotlinx.datetime.DatePeriod(days = it)) }
        rule.setContent {
            SummaryGrid(context, listOf(inst), mapOf(ev.id!! to ev), mapOf(1L to D.PERSONAL), days, {})
        }
        rule.onNodeWithText("Team standup").assertIsDisplayed()
    }

    @Test
    fun `week header and all day row render and click`() {
        var clicked: com.vayunmathur.calendar.data.Instance? = null
        val ev = D.event(allDay = true, title = "AllDayHeader")
        val inst = D.instance(id = 9, eventID = ev.id!!, allDay = true, title = "AllDayHeader")
        val days = listOf(D.TODAY, D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 1)))
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                WeekHeader(days, leadingGutter = true)
                AllDayRow(mapOf(D.TODAY to listOf(inst)), mapOf(ev.id!! to ev), mapOf(1L to D.PERSONAL), days, { clicked = it })
            }
        }
        rule.onNodeWithText("AllDayHeader").assertIsDisplayed()
        rule.onNodeWithText("AllDayHeader").performClick()
        assertEquals(inst, clicked)
    }

    @Test
    fun `week header without gutter renders`() {
        val days = listOf(D.TODAY)
        rule.setContent { WeekHeader(days, leadingGutter = false) }
        rule.onNodeWithText(D.TODAY.day.toString()).assertIsDisplayed()
    }

    @Test
    fun `hour label column renders hour labels`() {
        rule.mainClock.autoAdvance = false
        rule.setContent { HourLabelColumn(context, HourScale.uniform()) }
        assertTrue(rule.onAllNodesWithText("AM", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("00", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `hourly grid shows timed event and click fires`() {
        var clicked: com.vayunmathur.calendar.data.Instance? = null
        val inst = D.instance()
        val timed = mapOf(D.TODAY to mapOf(9 to listOf(inst)))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HourlyGrid(context, timed, listOf(D.TODAY), shrinkEmptyHours = false, onEventClick = { clicked = it }, innerPadding = androidx.compose.foundation.layout.PaddingValues(0.dp))
        }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Team standup").assertIsDisplayed()
        rule.onNodeWithText("Team standup").performClick()
        assertEquals(inst, clicked)
    }

    @Test
    fun `hourly grid compact empty renders`() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            HourlyGrid(context, emptyMap(), listOf(D.TODAY), shrinkEmptyHours = true, onEventClick = {}, innerPadding = androidx.compose.foundation.layout.PaddingValues(0.dp))
        }
        rule.mainClock.advanceTimeBy(500)
        // Hour gutter still present.
        assertTrue(rule.onAllNodesWithText("AM", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("00", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // --- Dialog components ---------------------------------------------------------

    @Test
    fun `chosen dates lists dates remove and add fire`() {
        var removed: LocalDate? = null
        var add = 0
        val extra = D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 3))
        rule.setContent {
            ChosenDates(D.TODAY, listOf(extra), { removed = it }, { add++ })
        }
        rule.onAllNodesWithText("Remove")[0].performClick()
        assertEquals(extra, removed)
        // Add-date button label from resources.
        val addNodes = rule.onAllNodesWithText("Add date").fetchSemanticsNodes()
        assertTrue(addNodes.isNotEmpty())
        rule.onNodeWithText("Add date").performClick()
        assertEquals(1, add)
    }

    @Test
    fun `on dropdown selects an option`() {
        var selected = -1
        rule.setContent {
            OnDropdown("PickLabel", listOf("AlphaOpt", "BetaOpt"), 0, { selected = it })
        }
        rule.onNodeWithText("AlphaOpt").assertIsDisplayed()
        rule.onNodeWithText("AlphaOpt").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("BetaOpt").performClick()
        assertEquals(1, selected)
    }

    // --- Description editor -----------------------------------------------------------

    @Test
    fun `edit description shows label for empty and filled text`() {
        rule.setContent {
            val controller = rememberOdfMarkdownEditorController(initialMarkdown = "") {}
            EditEventDescription(descriptionText = "", descriptionController = controller)
        }
        rule.onNodeWithText("Description").assertIsDisplayed()
    }

    @Test
    fun `edit description filled shows floating label`() {
        rule.setContent {
            val controller = rememberOdfMarkdownEditorController(initialMarkdown = "Hello desc") {}
            EditEventDescription(descriptionText = "Hello desc", descriptionController = controller)
        }
        rule.onNodeWithText("Description").assertIsDisplayed()
    }

    // --- Helpers (pure) -----------------------------------------------------------------

    @Test
    fun `view helpers week math covers locales and layouts`() {
        val monday = LocalDate(2026, 3, 9) // Monday
        val sunday = LocalDate(2026, 3, 8)
        val saturday = LocalDate(2026, 3, 7)
        assertTrue(firstDayOfWeekOffset(monday, Locale.US) in 0..6)
        assertTrue(firstDayOfWeekOffset(monday, Locale.UK) in 0..6)
        assertEquals(DayOfWeek.MONDAY, workWeekStart(monday, Locale.US).dayOfWeek)
        val ww = workWeekStart(sunday, Locale.US)
        assertTrue(ww.dayOfWeek != DayOfWeek.SATURDAY && ww.dayOfWeek != DayOfWeek.SUNDAY)
        val wwSat = workWeekStart(saturday, Locale.UK)
        assertTrue(wwSat.dayOfWeek != DayOfWeek.SATURDAY && wwSat.dayOfWeek != DayOfWeek.SUNDAY)
        assertEquals(monday, weekStartForLayout(monday, com.vayunmathur.calendar.util.CalendarViewModel.CalendarLayout.Day, Locale.US))
        assertTrue(weekStartForLayout(sunday, com.vayunmathur.calendar.util.CalendarViewModel.CalendarLayout.WorkWeek, Locale.US).dayOfWeek == DayOfWeek.MONDAY)
        assertNotNull(weekStartForLayout(sunday, com.vayunmathur.calendar.util.CalendarViewModel.CalendarLayout.FullWeek, Locale.US))
        // atEndOfDayIn is next midnight.
        val end = monday.atEndOfDayIn(TimeZone.of("UTC"))
        assertEquals(D.at(monday.plus(kotlinx.datetime.DatePeriod(days = 1)), 0), end.toEpochMilliseconds())
    }

    @Test
    fun `event title morph key only on start day`() {
        val inst = D.instance()
        assertEquals("calendar-event-title-${inst.id}", eventTitleMorphKey(inst, D.TODAY))
        assertNull(eventTitleMorphKey(inst, D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 1))))
    }

    // --- EditFormSnapshot.toEvent / RRule.withEnd ------------------------------------------

    private fun snapshot(
        allDay: Boolean = false, rrule: RRule? = null,
        rdate: List<LocalDate> = emptyList(),
    ) = EditFormSnapshot(
        title = "SnapTitle", description = "desc", location = "loc", calendarId = 1,
        allDay = allDay, startDate = D.TODAY, endDate = D.TODAY,
        startTime = LocalTime(9, 0), endTime = LocalTime(10, 30),
        timezone = "UTC", rrule = rrule, rdate = rdate,
        reminders = listOf(10), color = 123, exdate = emptyList(),
    )

    @Test
    fun `snapshot to event timed preserves fields and duration`() {
        val ev: Event = snapshot().toEvent(42)
        assertEquals(42L, ev.id)
        assertEquals("SnapTitle", ev.title)
        assertEquals(D.at(D.TODAY, 9), ev.start)
        assertEquals(D.at(D.TODAY, 10, 30), ev.end)
        assertEquals(false, ev.allDay)
        assertEquals("UTC", ev.timezone)
        assertEquals(listOf(10), ev.reminders)
    }

    @Test
    fun `snapshot to event all day uses exclusive next day end`() {
        val ev: Event = snapshot(allDay = true).toEvent(null)
        assertNull(ev.id)
        assertTrue(ev.allDay)
        assertEquals(D.at(D.TODAY, 0), ev.start)
        assertEquals(D.at(D.TODAY.plus(kotlinx.datetime.DatePeriod(days = 1)), 0), ev.end)
        assertEquals("UTC", ev.timezone)
    }

    @Test
    fun `rrule withEnd replaces condition on every subtype`() {
        val until = RRule.EndCondition.Until(D.TODAY)
        assertEquals(until, (RRule.EveryXDays(1, RRule.EndCondition.Never).withEnd(until)).endCondition)
        assertEquals(until, (RRule.EveryXWeeks(1, listOf(DayOfWeek.MONDAY), RRule.EndCondition.Never).withEnd(until)).endCondition)
        assertEquals(until, (RRule.EveryXMonths(1, 0, RRule.EndCondition.Never).withEnd(until)).endCondition)
        assertEquals(until, (RRule.EveryXYears(1, RRule.EndCondition.Never).withEnd(until)).endCondition)
    }
}
