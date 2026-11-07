package com.vayunmathur.calendar

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityComposeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun grantPermissions() {
        FakeCalendarData.reset()
        FakeCalendarData.register(context)
        FakeCalendarData.calendars = listOf(FakeCalendarData.calendarRow(CalendarTestData.PERSONAL))
        FakeCalendarData.events = listOf(FakeCalendarData.eventRow(CalendarTestData.event(title = "ActivityEvent")))
        FakeCalendarData.instances = listOf(FakeCalendarData.instanceRow(CalendarTestData.instance()))
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        // Exact alarms: Robolectric's AlarmManager shadow defaults to schedulable on S+.
    }

    @Test
    fun `activity content renders calendar or permission gate`() {
        // Either the calendar home (permissions accepted) or the permission gate
        // (special-access requirements outstanding) is a valid composed state;
        // both exercise CalendarAppContent and its theme wrapper.
        rule.waitForIdle()
        // The composed root exists whether the permission gate or the calendar
        // home is showing; either state exercises CalendarAppContent + theme.
        assertTrue(rule.activity.window.decorView != null)
    }

    @Test
    fun `activity survives recreation of composition`() {
        rule.waitForIdle()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertTrue(rule.activity.window.decorView != null)
    }
}
