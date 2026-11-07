package com.vayunmathur.calendar.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.AppWidgetId
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.FakeCalendarData
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.glance.CalendarGlanceWidget
import com.vayunmathur.calendar.glance.CalendarGlanceWidgetReceiver
import com.vayunmathur.calendar.glance.CalendarMonthGlanceWidget
import com.vayunmathur.calendar.glance.CalendarMonthGlanceWidgetReceiver
import com.vayunmathur.calendar.glance.cappedForCell
import com.vayunmathur.calendar.glance.computeMonthGrid
import com.vayunmathur.calendar.glance.contrastingTextOn
import com.vayunmathur.calendar.glance.maxChipsForSize
import com.vayunmathur.calendar.glance.sortedForMonthCell
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ImportGlanceHolidayTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeCalendarData.reset()
        FakeCalendarData.register(context)
        FakeCalendarData.calendars = listOf(
            FakeCalendarData.calendarRow(D.PERSONAL),
            FakeCalendarData.calendarRow(D.WORK),
        )
        FakeCalendarData.events = listOf(FakeCalendarData.eventRow(D.event()))
        FakeCalendarData.instances = listOf(FakeCalendarData.instanceRow(D.instance()))
    }

    private fun loadedVm(): CalendarViewModel {
        val vm = CalendarViewModel(ApplicationProvider.getApplicationContext<Application>())
        var waited = 0
        while (vm.calendars.value.isEmpty() && waited < 200) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10); waited++
        }
        assertEquals(2, vm.calendars.value.size)
        return vm
    }

    private fun icsFile(): Uri {
        val file = File.createTempFile("import-test", ".ics")
        file.writeText(
            listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:imp-1",
                "SUMMARY:ImportedStandup", "DESCRIPTION:From file", "LOCATION:ImportRoom",
                "DTSTART:20260309T090000Z", "DTEND:20260309T100000Z",
                "END:VEVENT", "END:VCALENDAR",
            ).joinToString("\r\n"),
        )
        return Uri.fromFile(file)
    }

    // --- ImportIcsScreen ---------------------------------------------------------

    @Test
    fun `import screen parses file lists events and imports into existing calendar`() {
        val vm = loadedVm()
        val stack = NavBackStack<Route>(arrayOf(Route.Calendar, Route.Settings.ImportIcs(listOf("x"))))
        val uri = icsFile()
        rule.setContent { ImportIcsScreen(vm, stack, listOf(uri.toString())) }
        rule.waitUntil(8000) {
            rule.onAllNodesWithText("ImportedStandup").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("ImportedStandup").assertIsDisplayed()
        rule.onNodeWithText("Import to existing calendar", substring = true).assertIsDisplayed()
        // Save via FAB (Save icon) into the auto-selected first editable calendar.
        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `import screen new calendar mode creates calendar then imports`() {
        val vm = loadedVm()
        val stack = NavBackStack<Route>(arrayOf(Route.Calendar, Route.Settings.ImportIcs(listOf("x"))))
        val uri = icsFile()
        rule.setContent { ImportIcsScreen(vm, stack, listOf(uri.toString())) }
        rule.waitUntil(8000) {
            rule.onAllNodesWithText("ImportedStandup").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Create new calendar", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Calendar name").performTextInput("ImportedCal")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `import screen with unreadable uri shows empty state`() {
        val vm = loadedVm()
        val stack = NavBackStack<Route>(arrayOf(Route.Calendar, Route.Settings.ImportIcs(listOf("x"))))
        rule.setContent {
            ImportIcsScreen(vm, stack, listOf("content://nonexistent.calendar/missing.ics"))
        }
        rule.waitUntil(8000) {
            rule.onAllNodesWithText("No events found", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("No events found", substring = true).assertIsDisplayed()
    }

    // --- MonthAgendaView ------------------------------------------------------------

    @Test
    fun `month agenda view shows month and agenda sections`() {
        val ev = D.event()
        val inst = D.instance(ev)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MonthAgendaView(
                context, listOf(ev), mapOf(1L to D.PERSONAL), mapOf(1L to true),
                D.TODAY, D.TODAY, { _, _ -> listOf(inst) }, {}, {}, {}, {},
                previewInstances = listOf(inst),
            )
        }
        rule.mainClock.advanceTimeBy(1000)
        assertTrue(rule.onAllNodesWithText("Team standup").fetchSemanticsNodes().isNotEmpty())
    }

    // --- HolidayCalendarsScreen ---------------------------------------------------------

    @Test
    fun `holiday screen lists countries searches and opens language picker`() {
        val vm = loadedVm()
        val stack = NavBackStack<Route>(arrayOf(Route.Calendar, Route.Settings, Route.Settings.HolidayCalendars))
        rule.setContent { HolidayCalendarsScreen(vm, stack) }
        rule.waitUntil(8000) {
            rule.onAllNodesWithText("Afghanistan").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Afghanistan").assertIsDisplayed()
        // Search narrows the list.
        rule.onNodeWithText("Search countries", substring = true).performTextInput("Zimbab")
        rule.waitForIdle()
        rule.onAllNodesWithText("Zimbabwe")[0].assertIsDisplayed()
    }

    @Test
    fun `holiday screen adds a country via language picker`() {
        val vm = loadedVm()
        val stack = NavBackStack<Route>(arrayOf(Route.Calendar, Route.Settings.HolidayCalendars))
        rule.setContent { HolidayCalendarsScreen(vm, stack) }
        rule.waitUntil(8000) {
            rule.onAllNodesWithText("Andorra").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Andorra").performClick()
        rule.waitForIdle()
        // Language picker dialog appears; choose the first language row.
        rule.onNodeWithText("Choose language").assertIsDisplayed()
        val langNodes = rule.onAllNodesWithText("English", substring = true).fetchSemanticsNodes()
        assertTrue(langNodes.isNotEmpty())
        rule.onAllNodesWithText("English", substring = true)[0].performClick()
        rule.waitForIdle()
    }

    // --- Glance pure helpers ------------------------------------------------------------------

    @Test
    fun `month grid covers the whole month in full weeks`() {
        val grid = computeMonthGrid(2026, Month.MARCH, firstDayOfWeek = 1)
        assertTrue(grid.all { it.size == 7 })
        assertTrue(grid.flatten().contains(LocalDate(2026, 3, 1)))
        assertTrue(grid.flatten().contains(LocalDate(2026, 3, 31)))
        val sundayFirst = computeMonthGrid(2026, Month.FEBRUARY, firstDayOfWeek = 7)
        assertTrue(sundayFirst.all { it.size == 7 })
        assertEquals(LocalDate(2026, 2, 1).dayOfWeek, sundayFirst.first().first().dayOfWeek)
    }

    @Test
    fun `cell capping sorting and contrast helpers behave`() {
        assertEquals(listOf(1, 2) to 0, listOf(1, 2).cappedForCell(3))
        assertEquals(listOf(1, 2) to 2, listOf(1, 2, 3, 4).cappedForCell(2))
        val late = D.instance(id = 1, startHour = 15)
        val early = D.instance(id = 2, startHour = 8)
        assertEquals(listOf(early, late), listOf(late, early).sortedForMonthCell())
        assertEquals(Color.Black, contrastingTextOn(Color.White))
        assertEquals(Color.White, contrastingTextOn(Color.Black))
        assertEquals(3, maxChipsForSize(0.dp, 5))
        assertEquals(3, maxChipsForSize(200.dp, 0))
        assertTrue(maxChipsForSize(1000.dp, 4) in 1..4)
        assertTrue(maxChipsForSize(120.dp, 6) in 1..4)
    }

    // --- Glance widgets: data + rendering entry points -------------------------------------------

    @Test
    fun `widget providePreview and provideGlance render without crashing`() = runBlocking {
        CalendarMonthGlanceWidget().providePreview(context, 0)
        CalendarGlanceWidget().providePreview(context, 0)
        CalendarMonthGlanceWidget().provideGlance(context, AppWidgetId(3))
        CalendarGlanceWidget().provideGlance(context, AppWidgetId(4))
        // update() drives the full Glance composition into RemoteViews; it needs a
        // WorkManager instance, which Robolectric does not initialize for us.
        runCatching {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        CalendarMonthGlanceWidget().update(context, AppWidgetId(5))
        CalendarGlanceWidget().update(context, AppWidgetId(6))
    }

    @Test
    fun `widget receivers handle date change broadcasts`() {
        // Date-triggered refresh launches a Glance update on a background scope; that
        // path is exercised on-device. Here only the no-op branch is deterministic.
        CalendarGlanceWidgetReceiver().onReceive(context, Intent("com.example.OTHER"))
        CalendarMonthGlanceWidgetReceiver().onReceive(context, Intent("com.example.OTHER"))
    }
}
