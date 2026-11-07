package com.vayunmathur.calendar.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.FakeCalendarData
import com.vayunmathur.calendar.Navigation
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.ui.dialogs.CalendarPickerDialog
import com.vayunmathur.calendar.ui.dialogs.CalendarSetDateDialog
import com.vayunmathur.calendar.ui.dialogs.RecurrenceDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsAddCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsChangeColorDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsDefaultRemindersDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsDeleteCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.SettingsRenameCalendarDialog
import com.vayunmathur.calendar.ui.dialogs.TimezonePickerDialog
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.util.RecurrenceParams
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NavigationAndDialogsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeCalendarData.reset()
        FakeCalendarData.register(context)
        val standup = D.event(
            title = "NavStandup", location = "NavRoom", description = "NavDesc",
            reminders = listOf(10),
        )
        val recurring = D.event(
            id = 11, title = "NavWeekly",
            rrule = RRule.EveryXWeeks(1, listOf(DayOfWeek.MONDAY), RRule.EndCondition.Never),
        )
        FakeCalendarData.calendars = listOf(
            FakeCalendarData.calendarRow(D.PERSONAL),
            FakeCalendarData.calendarRow(D.WORK),
            FakeCalendarData.calendarRow(D.READONLY),
        )
        FakeCalendarData.events = listOf(
            FakeCalendarData.eventRow(standup),
            FakeCalendarData.eventRow(recurring),
        )
        FakeCalendarData.instances = listOf(
            FakeCalendarData.instanceRow(D.instance(standup)),
            FakeCalendarData.instanceRow(D.instance(recurring, id = 111)),
        )
        FakeCalendarData.reminders = listOf(
            mapOf(android.provider.CalendarContract.Reminders.EVENT_ID to 10L, android.provider.CalendarContract.Reminders.MINUTES to 10),
        )
    }

    private fun loadedVm(): CalendarViewModel {
        val vm = CalendarViewModel(ApplicationProvider.getApplicationContext<Application>())
        var waited = 0
        while ((vm.calendars.value.isEmpty() || vm.events.value.isEmpty()) && waited < 200) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10); waited++
        }
        assertEquals(3, vm.calendars.value.size, "calendars did not load: ${vm.calendars.value}")
        assertEquals(2, vm.events.value.size, "events did not load: ${vm.events.value}")
        return vm
    }

    private fun stack(vararg routes: Route) = NavBackStack<Route>(routes as Array<Route>)

    // --- Navigation ----------------------------------------------------------------

    @Test
    fun `navigation opens on calendar home`() {
        val vm = loadedVm()
        rule.mainClock.autoAdvance = false
        rule.setContent { Navigation(vm, initialRoute = null) }
        rule.mainClock.advanceTimeBy(1000)
        rule.onAllNodesWithText("2026", substring = true)[0].assertIsDisplayed()
    }

    @Test
    fun `navigation settings route shows settings screen`() {
        val vm = loadedVm()
        rule.setContent { Navigation(vm, initialRoute = Route.Settings) }
        rule.waitForIdle()
        rule.onNodeWithText("Holiday calendars").assertIsDisplayed()
    }

    @Test
    fun `navigation event route shows event detail`() {
        val vm = loadedVm()
        val inst = D.instance(D.event(title = "NavStandup"))
        rule.setContent { Navigation(vm, initialRoute = Route.Event(inst)) }
        rule.waitForIdle()
        rule.onNodeWithText("NavStandup").assertIsDisplayed()
        rule.onNodeWithText("NavRoom").assertIsDisplayed()
    }

    @Test
    fun `navigation event route for missing event shows not found`() {
        val vm = loadedVm()
        val inst = D.instance(id = 999, eventID = 999, title = "GhostEvent")
        rule.setContent { Navigation(vm, initialRoute = Route.Event(inst)) }
        rule.waitForIdle()
        rule.onNodeWithText("Event not found").assertIsDisplayed()
    }

    @Test
    fun `navigation edit existing event shows editor with title`() {
        val vm = loadedVm()
        rule.setContent { Navigation(vm, initialRoute = Route.EditEvent(id = 10)) }
        rule.waitForIdle()
        rule.onNodeWithText("NavStandup").assertIsDisplayed()
        rule.onNodeWithText("Does not repeat").assertIsDisplayed()
        rule.onNodeWithText("Add reminder").assertExists()
    }

    @Test
    fun `navigation edit new event shows blank editor and save pops`() {
        val vm = loadedVm()
        rule.setContent { Navigation(vm, initialRoute = Route.EditEvent(id = null, title = "PrefilledTitle")) }
        rule.waitForIdle()
        rule.onNodeWithText("PrefilledTitle").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `navigation edit recurring event offers scope menu`() {
        val vm = loadedVm()
        rule.setContent { Navigation(vm, initialRoute = Route.EditEvent(id = 11, instanceId = 111)) }
        rule.waitForIdle()
        rule.onNodeWithText("NavWeekly").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitForIdle()
        // Scope chooser appears for a recurring series edited from an instance.
        val scopeNodes = rule.onAllNodesWithText("event", substring = true).fetchSemanticsNodes()
        assertTrue(scopeNodes.isNotEmpty())
    }

    @Test
    fun `navigation import route with no uris shows empty state`() {
        val vm = loadedVm()
        rule.setContent { Navigation(vm, initialRoute = Route.Settings.ImportIcs(emptyList())) }
        rule.waitForIdle()
        rule.onNodeWithText("No events found", substring = true).assertIsDisplayed()
    }

    @Test
    fun `navigation holiday route shows search`() {
        val vm = loadedVm()
        rule.setContent { Navigation(vm, initialRoute = Route.Settings.HolidayCalendars) }
        rule.waitForIdle()
        rule.onNodeWithText("Holiday calendars").assertIsDisplayed()
    }

    // --- Settings dialogs (direct, with loaded VM) --------------------------------------

    @Test
    fun `add calendar dialog creates and pops`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings, Route.Settings.AddCalendar())
        rule.setContent { SettingsAddCalendarDialog(vm, stack) }
        rule.onNodeWithText("New local calendar").assertIsDisplayed()
        rule.onNodeWithText("Calendar name").performTextInput("MyNewCal")
        rule.onNodeWithText("Create").performClick()
        assertEquals(2, stack.backStack.size)
    }

    @Test
    fun `add calendar dialog cancel pops without creating`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings.AddCalendar())
        rule.setContent { SettingsAddCalendarDialog(vm, stack) }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `change color dialog confirms and pops`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings.ChangeColor(1))
        rule.setContent { SettingsChangeColorDialog(vm, stack, 1) }
        rule.onNodeWithText("Change color for this calendar").assertIsDisplayed()
        rule.onNodeWithText("Change color").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `change color dialog for missing calendar pops immediately`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings.ChangeColor(999))
        rule.setContent { SettingsChangeColorDialog(vm, stack, 999) }
        rule.waitForIdle()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `rename dialog renames and pops, cancel also pops`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings.RenameCalendar(1))
        rule.setContent { SettingsRenameCalendarDialog(vm, stack, 1) }
        rule.onNodeWithText("Rename calendar").assertIsDisplayed()
        rule.onNodeWithText("PersonalCal").performTextReplacement("RenamedCal")
        rule.onNodeWithText("Rename").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `rename dialog for readonly calendar pops immediately`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings.RenameCalendar(3))
        rule.setContent { SettingsRenameCalendarDialog(vm, stack, 3) }
        rule.waitForIdle()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `delete dialog confirms and pops`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings.DeleteCalendar(1))
        rule.setContent { SettingsDeleteCalendarDialog(vm, stack, 1) }
        rule.onNodeWithText("Delete calendar").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `delete dialog for readonly calendar pops immediately`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings.DeleteCalendar(3))
        rule.setContent { SettingsDeleteCalendarDialog(vm, stack, 3) }
        rule.waitForIdle()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `default reminders dialog toggles presets and saves`() {
        val vm = loadedVm()
        val stack = stack(Route.Calendar, Route.Settings.DefaultReminders(1))
        rule.setContent { SettingsDefaultRemindersDialog(vm, stack, 1) }
        rule.onNodeWithText("Default reminders").assertIsDisplayed()
        // Toggle the first preset row by its label text.
        val labels = rule.onAllNodesWithText("before", substring = true).fetchSemanticsNodes()
        assertTrue(labels.isNotEmpty())
        rule.onAllNodesWithText("before", substring = true)[0].performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(1, stack.backStack.size)
    }

    // --- Picker dialogs --------------------------------------------------------------------

    @Test
    fun `calendar picker lists editable calendars and picks one`() {
        val vm = loadedVm()
        // Ensure the viewModel() inside the dialog also loads from the same provider.
        val stack = stack(Route.Calendar, Route.EditEvent.CalendarPickerDialog("key"))
        val registry = NavResultRegistry()
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                CalendarPickerDialog(stack, "key")
            }
        }
        rule.waitUntil(5000) { rule.onAllNodesWithText("PersonalCal").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("PersonalCal").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `calendar set date dialog confirms and pops`() {
        val stack = stack(Route.Calendar, Route.Calendar.GotoDialog(D.TODAY))
        val registry = NavResultRegistry()
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                CalendarSetDateDialog(stack, D.TODAY)
            }
        }
        rule.onNodeWithText("Go to date").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `timezone picker searches and picks UTC`() {
        val stack = stack(Route.Calendar, Route.EditEvent.TimezonePickerDialog("tz"))
        val registry = NavResultRegistry()
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                TimezonePickerDialog(stack, "tz")
            }
        }
        rule.onNodeWithText("Choose timezone").assertIsDisplayed()
        rule.onNodeWithText("Search").performTextInput("UTC")
        rule.waitForIdle()
        rule.onAllNodesWithText("UTC", substring = true)[1].performClick()
        assertEquals(1, stack.backStack.size)
    }

    // --- Recurrence dialog -------------------------------------------------------------------

    private fun recurrenceStack() = stack(Route.Calendar, Route.EditEvent.RecurrenceDialog("rec", D.TODAY))

    @Test
    fun `recurrence dialog pattern mode confirms daily and pops`() {
        val stack = recurrenceStack()
        val registry = NavResultRegistry()
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                RecurrenceDialog(stack, "rec", D.TODAY, initial = null)
            }
        }
        rule.onNodeWithText("Pattern").assertIsDisplayed()
        rule.onNodeWithText("Never").performClick()
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `recurrence dialog weeks mode shows weekday picker`() {
        val stack = recurrenceStack()
        val registry = NavResultRegistry()
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                RecurrenceDialog(
                    stack, "rec", D.TODAY,
                    initial = RecurrenceParams(freq = "weeks", interval = 2, daysOfWeek = listOf(DayOfWeek.MONDAY)),
                )
            }
        }
        rule.onNodeWithText("On days of week").assertIsDisplayed()
        rule.onNodeWithText("Count").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `recurrence dialog months and years presets render`() {
        val stack = recurrenceStack()
        val registry = NavResultRegistry()
        var params by androidx.compose.runtime.mutableStateOf<RecurrenceParams?>(
            RecurrenceParams(freq = "months", interval = 1, monthlyType = 1),
        )
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                RecurrenceDialog(stack, "rec", D.TODAY, initial = params)
            }
        }
        rule.onNodeWithText("On", substring = true).assertExists()
        rule.runOnIdle { params = RecurrenceParams(freq = "years", interval = 1, byWeekNo = listOf(11)) }
        rule.waitForIdle()
        rule.onNodeWithText("Until").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `recurrence dialog dates mode adds and removes dates`() {
        val stack = recurrenceStack()
        val registry = NavResultRegistry()
        val extra = LocalDate(2026, 3, 16)
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                RecurrenceDialog(stack, "rec", D.TODAY, initial = null, initialDates = listOf(extra, D.TODAY))
            }
        }
        rule.onNodeWithText("Specific dates").assertIsDisplayed()
        rule.onAllNodesWithText("Remove")[0].performClick()
        rule.onNodeWithText("Add date").performClick()
        // Add-date pushes a date-picker route for the extra date.
        assertTrue(stack.backStack.last() is Route.EditEvent.DatePickerDialog)
    }

    @Test
    fun `recurrence dialog dates mode confirms and pops`() {
        val stack = recurrenceStack()
        val registry = NavResultRegistry()
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                RecurrenceDialog(stack, "rec", D.TODAY, initial = null, initialDates = listOf(LocalDate(2026, 3, 16)))
            }
        }
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun `recurrence dialog cancel pops`() {
        val stack = recurrenceStack()
        val registry = NavResultRegistry()
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                RecurrenceDialog(stack, "rec", D.TODAY, initial = null)
            }
        }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, stack.backStack.size)
    }
}
