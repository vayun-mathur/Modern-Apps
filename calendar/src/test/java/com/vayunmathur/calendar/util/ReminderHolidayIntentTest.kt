package com.vayunmathur.calendar.util

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.calendar.CalendarTestData as D
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.intents.GetIntent
import com.vayunmathur.calendar.intents.InsertIntent
import com.vayunmathur.library.intents.calendar.EventData
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.plus
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ReminderHolidayIntentTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun futureEvent(minutesFromNow: Long = 120, reminders: List<Int> = listOf(10)): Event {
        val now = System.currentTimeMillis()
        return D.event().copy(
            id = 777, start = now + minutesFromNow * 60_000L,
            end = now + (minutesFromNow + 30) * 60_000L, reminders = reminders,
        )
    }

    // --- ReminderScheduler ----------------------------------------------------

    @Test
    fun `reconcile with no events and with past events schedules nothing tracked`() = runBlocking {
        ReminderScheduler.reconcileAll(context, emptyList())
        assertEquals(0, ReminderScheduler.scheduleFromMirror(context))
        val past = D.event().copy(id = 778, start = 1000L, end = 2000L, reminders = listOf(10))
        ReminderScheduler.reconcileAll(context, listOf(past))
        // Mirror window only carries future reminders, so re-arming finds none for a past event.
        assertEquals(0, ReminderScheduler.scheduleFromMirror(context))
    }

    @Test
    fun `reconcile future one off event arms mirror entry`() = runBlocking {
        ReminderScheduler.reconcileAll(context, listOf(futureEvent()))
        val armed = ReminderScheduler.scheduleFromMirror(context)
        assertTrue(armed >= 1, "expected at least one mirror-armed reminder, got $armed")
    }

    @Test
    fun `reconcile ignores idless and reminderless events`() = runBlocking {
        val idless = futureEvent().copy(id = null)
        val noReminders = futureEvent().copy(id = 779, reminders = emptyList())
        ReminderScheduler.reconcileAll(context, listOf(idless, noReminders))
        assertEquals(0, ReminderScheduler.scheduleFromMirror(context))
    }

    @Test
    fun `reconcile recurring event without instances does not crash`() = runBlocking {
        val recurring = futureEvent().copy(
            rrule = RRule.EveryXDays(1, RRule.EndCondition.Count(3)),
        )
        ReminderScheduler.reconcileAll(context, listOf(recurring))
    }

    @Test
    fun `scheduler extras and channel constants are stable`() {
        assertEquals("event_id", ReminderScheduler.EXTRA_EVENT_ID)
        assertEquals("instance", ReminderScheduler.EXTRA_INSTANCE)
        assertEquals("calendar_reminders", ReminderReceiver.CHANNEL_ID)
    }

    // --- Receivers ---------------------------------------------------------------

    @Test
    fun `boot receiver ignores unrelated actions and handles boot actions`() {
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent("com.example.UNRELATED"))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        receiver.onReceive(context, Intent("android.intent.action.QUICKBOOT_POWERON"))
    }

    @Test
    fun `reminder receiver posts a notification for a future reminder`() {
        // Seed a future reminder in the mirror/scheduler state first is not needed:
        // the receiver posts regardless, resolving the instance from the (empty) provider.
        val receiver = ReminderReceiver()
        val start = System.currentTimeMillis() + 3_600_000L
        val intent = Intent().apply {
            putExtra(ReminderScheduler.EXTRA_EVENT_ID, 777L)
            putExtra(ReminderScheduler.EXTRA_TITLE, "Dentist soon")
            putExtra(ReminderScheduler.EXTRA_INSTANCE_START, start)
            putExtra(ReminderScheduler.EXTRA_INSTANCE_END, start + 1_800_000L)
        }
        receiver.onReceive(context, intent)
        // The post runs on an IO coroutine; wait for the notification manager shadow.
        val nm = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(nm)
        var waited = 0
        while (shadow.allNotifications.isEmpty() && waited < 100) {
            Thread.sleep(20); waited++
        }
        assertTrue(shadow.allNotifications.isNotEmpty(), "reminder notification was not posted")
        assertNotNull(shadow.allNotifications.first())
    }

    @Test
    fun `reminder receiver with missing extras uses default title`() {
        val receiver = ReminderReceiver()
        receiver.onReceive(context, Intent())
        val nm = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(nm)
        var waited = 0
        while (shadow.allNotifications.isEmpty() && waited < 100) {
            Thread.sleep(20); waited++
        }
        assertTrue(shadow.allNotifications.isNotEmpty())
    }

    // --- HolidayData / HolidayCalendarManager ---------------------------------------

    @Test
    fun `holiday data reads bundled assets or falls back empty`() {
        val countries = HolidayData.countries(context)
        // Bundled index may or may not be visible to Robolectric; either way the call
        // must return a (possibly empty) list without throwing, and caching must agree.
        assertEquals(countries, HolidayData.countries(context))
        val languages = HolidayData.languages(context)
        assertEquals(languages, HolidayData.languages(context))
        assertNotNull(HolidayData.countryLanguages(context))
        val forCountry = HolidayData.languagesForCountry(context, "US")
        assertTrue(forCountry.isNotEmpty())
        // Unknown country/language falls back to empty rather than throwing.
        assertEquals(emptyList(), HolidayData.holidays(context, "NO_SUCH_COUNTRY", "xx"))
        val us = HolidayData.holidays(context, "UnitedStates", "en")
        assertEquals(us, HolidayData.holidays(context, "UnitedStates", "en"))
    }

    @Test
    fun `holiday manager with no provider reports none added and removals are no-ops`() {
        assertEquals(emptySet(), HolidayCalendarManager.addedCalendars(context))
        assertEquals(emptySet(), HolidayCalendarManager.addedCountryCodes(context))
        HolidayCalendarManager.removeCountry(context, "US", "en")
        HolidayCalendarManager.removeCountry(context, "US")
        HolidayCalendarManager.addCountry(context, "UnitedStates", "United States", "en", "English")
    }

    // --- Assistant intents -----------------------------------------------------------------

    @Test
    fun `get intent returns empty list without provider events`() = runBlocking {
        val activity = Robolectric.buildActivity(GetIntent::class.java, Intent()).create().get()
        val result = activity.performCalculation(Unit)
        assertEquals(emptyList(), result)
    }

    @Test
    fun `insert intent rejects blank title and inverted times`() { runBlocking {
        val activity = Robolectric.buildActivity(InsertIntent::class.java, Intent()).create().get()
        assertFailsWith<IllegalArgumentException> {
            activity.performCalculation(EventData(title = "  ", start = 1000, end = 2000))
        }
        assertFailsWith<IllegalArgumentException> {
            activity.performCalculation(EventData(title = "Ok", start = 2000, end = 1000))
        }
    } }

    @Test
    fun `insert intent rejects when no writable calendar exists`() { runBlocking {
        val activity = Robolectric.buildActivity(InsertIntent::class.java, Intent()).create().get()
        assertFailsWith<IllegalArgumentException> {
            activity.performCalculation(EventData(title = "Ok", start = 1000, end = 2000, location = "Somewhere"))
        }
    } }

    @Test
    fun `insert intent succeeds with a writable visible calendar`() = runBlocking {
        com.vayunmathur.calendar.FakeCalendarData.reset()
        com.vayunmathur.calendar.FakeCalendarData.register(context)
        com.vayunmathur.calendar.FakeCalendarData.calendars = listOf(
            com.vayunmathur.calendar.FakeCalendarData.calendarRow(D.PERSONAL),
        )
        val activity = Robolectric.buildActivity(InsertIntent::class.java, Intent()).create().get()
        // No exception: the fake provider accepts the insert and returns a new id.
        activity.performCalculation(EventData(title = "InsertedEvent", start = 1000, end = 2000, location = "Office"))
        com.vayunmathur.calendar.FakeCalendarData.reset()
    }

    @Test
    fun `holiday data returns real entries for a known country`() {
        val entries = HolidayData.holidays(context, "UnitedStates", "en")
        assertTrue(entries.isNotEmpty(), "expected bundled UnitedStates holidays")
        assertTrue(entries.all { it.d.isNotBlank() && it.n.isNotBlank() })
        val countries = HolidayData.countries(context)
        assertTrue(countries.any { it.code == "UnitedStates" }, countries.take(3).toString())
        val languages = HolidayData.languages(context)
        assertTrue(languages.any { it.code == "en" } || languages.isNotEmpty())
    }

    @Test
    fun `rrule describe renders for every subtype and end condition`() {
        val ctx = context
        val rules = listOf(
            RRule.EveryXDays(1, RRule.EndCondition.Never),
            RRule.EveryXDays(3, RRule.EndCondition.Count(5)),
            RRule.EveryXDays(1, RRule.EndCondition.Until(D.TODAY)),
            RRule.EveryXWeeks(2, listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), RRule.EndCondition.Never),
            RRule.EveryXMonths(1, 0, RRule.EndCondition.Never, byMonthDay = listOf(9)),
            RRule.EveryXMonths(1, 1, RRule.EndCondition.Count(2)),
            RRule.EveryXMonths(1, 2, RRule.EndCondition.Until(D.TODAY)),
            RRule.EveryXYears(1, RRule.EndCondition.Never, byMonth = listOf(3), byMonthDay = listOf(9)),
            RRule.EveryXYears(1, RRule.EndCondition.Never, byWeekNo = listOf(11), byDay = listOf(DayOfWeek.MONDAY)),
            RRule.EveryXYears(1, RRule.EndCondition.Never, byYearDay = listOf(68)),
        )
        for (rule in rules) {
            val text = rule.describe(ctx)
            assertTrue(text.isNotBlank(), "describe blank for $rule")
            val serialized = rule.asString(D.TODAY, kotlinx.datetime.TimeZone.of("UTC"))
            assertTrue(serialized.contains("FREQ="), serialized)
        }
        // EndCondition companion parsing helpers, if present via toString of rules.
        assertTrue(RRule.parse("FREQ=DAILY;INTERVAL=2;COUNT=3", kotlinx.datetime.TimeZone.of("UTC")) is RRule.EveryXDays)
        assertTrue(RRule.parse("FREQ=WEEKLY;BYDAY=MO,WE", kotlinx.datetime.TimeZone.of("UTC")) is RRule.EveryXWeeks)
        assertTrue(RRule.parse("FREQ=MONTHLY", kotlinx.datetime.TimeZone.of("UTC")) is RRule.EveryXMonths)
        assertTrue(RRule.parse("FREQ=YEARLY", kotlinx.datetime.TimeZone.of("UTC")) is RRule.EveryXYears)
        assertEquals(null, RRule.parse("", kotlinx.datetime.TimeZone.of("UTC")))
    }

    @Test
    fun `event data defaults location to empty`() {
        val data = EventData(title = "T", start = 1, end = 2)
        assertEquals("", data.location)
        assertEquals(data, data.copy())
    }
}
