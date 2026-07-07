package com.vayunmathur.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Color
import android.provider.CalendarContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Screenshot generator driven by `:calendar:metadata`. Seeds a local calendar with events
 * into the system CalendarProvider before launch, then captures the month view, an event
 * detail page, and the settings page.
 *
 * `pm clear` does NOT wipe the system CalendarProvider, so we delete previously-seeded local
 * calendars (which cascade-deletes their events) at the start of every run to stay idempotent.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun syncAdapterUri(uri: android.net.Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "Personal")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    /** Deletes local calendars (and their events) left over from a previous run. */
    private fun clearExisting() {
        ctx.contentResolver.delete(
            syncAdapterUri(CalendarContract.Calendars.CONTENT_URI),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL)
        )
    }

    private fun seedCalendar() {
        clearExisting()

        val resolver = ctx.contentResolver
        val calValues = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, "Personal")
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "Personal")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Personal")
            put(CalendarContract.Calendars.CALENDAR_COLOR, Color.parseColor("#4285F4"))
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, "Personal")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val calId = ContentUris.parseId(
            resolver.insert(syncAdapterUri(CalendarContract.Calendars.CONTENT_URI), calValues)!!
        )

        val tz = TimeZone.getDefault().id
        // (title, dayOffsetFromToday, startHour, durationHours, location)
        val events = listOf(
            Event("Team standup", 0, 9, 1, "Meeting Room B"),
            Event("Lunch with Alex", 0, 12, 1, "Cafe Rio"),
            Event("Dentist appointment", 1, 15, 1, "Downtown Dental"),
            Event("Yoga class", 2, 18, 1, "Studio 5"),
            Event("Project deadline", 3, 17, 1, ""),
            Event("Weekend hike", 5, 8, 4, "Trailhead"),
        )
        for (e in events) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, e.dayOffset)
                set(Calendar.HOUR_OF_DAY, e.startHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            val end = start + e.durationHours * 60L * 60L * 1000L
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, e.title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                if (e.location.isNotEmpty()) put(CalendarContract.Events.EVENT_LOCATION, e.location)
            }
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }
    }

    private data class Event(val title: String, val dayOffset: Int, val startHour: Int, val durationHours: Int, val location: String)

    @Test
    fun generateStoreScreenshots() {
        seedCalendar()
        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the ViewModel load events from the provider.
            Thread.sleep(3000)

            // Switch from the default Full Week view to Agenda, where seeded events render
            // as tappable list rows (the "W7" text is the layout switcher button).
            composeRule.onNodeWithText("W7").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Agenda").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Team standup").fetchSemanticsNodes().isNotEmpty()
            }

            // 2: event detail page.
            composeRule.onNodeWithText("Team standup").performClick()
            composeRule.waitForIdle()
            Thread.sleep(1500)
            snap(2)

            // Back to the calendar, then open Settings.
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()

            // 3: settings page.
            composeRule.onNodeWithContentDescription("Settings").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Default Layout").fetchSemanticsNodes().isNotEmpty()
            }
            Thread.sleep(1000)
            snap(3)

            // Back to the calendar, switch to Month for the lead screenshot.
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("A").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Month").performClick()
            composeRule.waitForIdle()
            Thread.sleep(1500)

            // 1: month view with events (lead).
            snap(1)
        }
    }
}
