package com.vayunmathur.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.calendar.util.ReminderScheduler
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun launch(intent: Intent) =
        Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume()

    @Test
    fun `plain launch creates activity`() {
        val controller = launch(Intent(Intent.ACTION_MAIN))
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `time epoch view intent is consumed as date jump`() {
        val ts = CalendarTestData.at(CalendarTestData.TODAY, 9)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar/time/$ts")
        }
        val controller = launch(intent)
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `time epoch typed intent is consumed`() {
        val ts = CalendarTestData.at(CalendarTestData.TODAY, 9)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "time/epoch"
            data = Uri.parse("content://com.android.calendar/time/$ts")
        }
        launch(intent).pause().stop().destroy()
    }

    @Test
    fun `instance json deep link is consumed`() {
        val inst = CalendarTestData.instance()
        val intent = Intent().apply {
            putExtra(ReminderScheduler.EXTRA_INSTANCE, Json.encodeToString(inst))
        }
        launch(intent).pause().stop().destroy()
    }

    @Test
    fun `malformed instance extra falls back without crashing`() {
        val intent = Intent().apply {
            putExtra(ReminderScheduler.EXTRA_INSTANCE, "{not-json")
        }
        launch(intent).pause().stop().destroy()
    }

    @Test
    fun `reminder fallback extras are consumed`() {
        val intent = Intent().apply {
            putExtra(ReminderScheduler.EXTRA_EVENT_ID, 42L)
            putExtra(ReminderScheduler.EXTRA_INSTANCE_START, CalendarTestData.at(CalendarTestData.TODAY, 9))
            putExtra(ReminderScheduler.EXTRA_INSTANCE_END, CalendarTestData.at(CalendarTestData.TODAY, 10))
        }
        launch(intent).pause().stop().destroy()
    }

    @Test
    fun `reminder fallback with invalid ids falls back`() {
        val intent = Intent().apply {
            putExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)
            putExtra(ReminderScheduler.EXTRA_INSTANCE_START, 0L)
            putExtra(ReminderScheduler.EXTRA_INSTANCE_END, 0L)
        }
        launch(intent).pause().stop().destroy()
    }

    @Test
    fun `insert intent with event extras is consumed`() {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = "vnd.android.cursor.dir/event"
            putExtra(CalendarContract.Events.TITLE, "FromOtherApp")
            putExtra(CalendarContract.Events.DESCRIPTION, "Desc")
            putExtra(CalendarContract.Events.EVENT_LOCATION, "Place")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, CalendarTestData.at(CalendarTestData.TODAY, 9))
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, CalendarTestData.at(CalendarTestData.TODAY, 10))
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        launch(intent).pause().stop().destroy()
    }

    @Test
    fun `insert intent without type is still consumed`() {
        val intent = Intent(Intent.ACTION_INSERT)
        launch(intent).pause().stop().destroy()
    }

    @Test
    fun `onNewIntent handles a second instance link`() {
        val controller = launch(Intent(Intent.ACTION_MAIN))
        val inst = CalendarTestData.instance(id = 555, eventID = 10)
        val second = Intent().apply {
            putExtra(ReminderScheduler.EXTRA_INSTANCE, Json.encodeToString(inst))
        }
        controller.newIntent(second)
        controller.pause().stop().destroy()
    }

    @Test
    fun `route keys have expected shapes`() {
        val edit = Route.EditEvent(id = null, title = "T", beginTime = 1L, endTime = 2L, allDay = true, instanceId = 7L)
        assertEquals("T", edit.title)
        assertEquals(7L, edit.instanceId)
        assertEquals(Route.Calendar, Route.Calendar)
        assertEquals(Route.Settings, Route.Settings)
        assertEquals(Route.Settings.HolidayCalendars, Route.Settings.HolidayCalendars)
        assertEquals(3L, Route.Settings.ChangeColor(3).id)
        assertEquals(0, Route.Settings.AddCalendar().placeholder)
        assertEquals(listOf("a.ics"), Route.Settings.ImportIcs(listOf("a.ics")).uris)
        val fallback = ReminderFallback(1L, 2L, 3L)
        assertEquals(1L, fallback.eventId)
        assertTrue(fallback.instanceEnd > fallback.instanceStart)
        val instanceRoute = Route.Event(CalendarTestData.instance())
        assertEquals(10L, instanceRoute.instance.eventID)
        assertNotNull(Route.EditEvent.DatePickerDialog("k", CalendarTestData.TODAY).key)
        assertNotNull(Route.EditEvent.RecurrenceDialog("k", CalendarTestData.TODAY).startDate)
        assertTrue(Instance.serializer().descriptor.serialName.isNotBlank())
    }
}
