package com.vayunmathur.health.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for the dose notification plumbing: channel ids, channel creation,
 * and posting/cancelling the reminder.
 *
 * One notification id for every dose is deliberate — a second dose replaces
 * the first rather than adding a second thing that rings — so these pin the
 * ids and the post/cancel round trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DoseNotificationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)!!

    @Test
    fun `channel and notification ids are stable`() {
        assertEquals("medication_reminder", DOSE_REMINDER_CHANNEL_ID)
        assertEquals("medication_ringing", DOSE_RINGING_CHANNEL_ID)
        assertEquals(200_001, DOSE_REMINDER_NOTIFICATION_ID)
        assertEquals(200_002, DOSE_SERVICE_NOTIFICATION_ID)
    }

    @Test
    fun `creating channels registers both`() {
        createDoseChannels(context)
        val channels = manager.notificationChannels
        assertTrue(channels.any { it.id == DOSE_REMINDER_CHANNEL_ID }, "reminder channel missing")
        assertTrue(channels.any { it.id == DOSE_RINGING_CHANNEL_ID }, "ringing channel missing")
    }

    @Test
    fun `posting then cancelling leaves nothing`() {
        DoseNotification.post(context, "sched-1", "med-1", "Amoxicillin 500 mg")
        val shadowManager = Shadows.shadowOf(manager)
        assertNotNull(
            shadowManager.getNotification(DOSE_REMINDER_NOTIFICATION_ID),
            "reminder was not posted",
        )
        DoseNotification.cancel(context)
        assertEquals(
            null,
            shadowManager.getNotification(DOSE_REMINDER_NOTIFICATION_ID),
            "reminder was not cancelled",
        )
    }

    @Test
    fun `the posted reminder carries taken and snooze actions`() {
        DoseNotification.post(context, "sched-1", "med-1", "Metformin")
        val posted = Shadows.shadowOf(manager).getNotification(DOSE_REMINDER_NOTIFICATION_ID)
        assertNotNull(posted)
        val actions = posted.actions.orEmpty().map { it.title.toString() }
        assertTrue(actions.any { "Taken" in it }, "no Taken action in $actions")
        assertTrue(actions.any { "Snooze" in it }, "no Snooze action in $actions")
        DoseNotification.cancel(context)
    }
}
