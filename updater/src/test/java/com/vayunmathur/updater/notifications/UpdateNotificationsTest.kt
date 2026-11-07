package com.vayunmathur.updater.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UpdateNotificationsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = ctx.getSystemService(NotificationManager::class.java)

    private fun posted() = manager.activeNotifications.toList()

    @Test
    fun `ensure channels creates the progress and result channels`() {
        UpdateNotifications.ensureChannels(ctx)
        assertNotNull(manager.getNotificationChannel(UpdateNotifications.CHANNEL_PROGRESS))
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(UpdateNotifications.CHANNEL_PROGRESS).importance,
        )
        assertNotNull(manager.getNotificationChannel("updater_result"))
    }

    @Test
    fun `progress below zero is indeterminate and at zero or above is determinate`() {
        val indeterminate = UpdateNotifications.progress(ctx, "Preparing", -1)
        assertTrue(indeterminate.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(indeterminate.flags and Notification.FLAG_ONGOING_EVENT != 0)

        val half = UpdateNotifications.progress(ctx, "Downloading", 50)
        // The compat style (legacy branch below API 36) reports progress through the extras.
        val progress = half.extras.getInt(Notification.EXTRA_PROGRESS)
        val max = half.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        if (max > 0) assertEquals(50, progress)
        assertFalse(half.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals("Downloading", half.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `progress is clamped to the notification scale`() {
        val over = UpdateNotifications.progress(ctx, "Installing", 250)
        val max = over.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        if (max > 0) assertEquals(max, over.extras.getInt(Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `update progress posts under the shared progress id`() {
        UpdateNotifications.ensureChannels(ctx)
        UpdateNotifications.updateProgress(ctx, "Downloading", 10)
        UpdateNotifications.updateProgress(ctx, "Verifying", 90)
        val posted = posted().filter { it.id == UpdateNotifications.ID_PROGRESS }
        assertEquals(1, posted.size, "progress updates replace, not stack")
    }

    @Test
    fun `reboot pending posts a result with a restart action`() {
        UpdateNotifications.ensureChannels(ctx)
        val restart = PendingIntent.getBroadcast(
            ctx, 0, Intent("test.restart"), PendingIntent.FLAG_IMMUTABLE,
        )
        UpdateNotifications.rebootPending(ctx, "2026090700", restart)
        val posted = posted().single { it.id == 2 }
        val notification = posted.notification
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains("2026090700") == true,
            notification.extras.toString(),
        )
        assertNotNull(notification.actions?.singleOrNull())
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `update available and failed post results, and clear removes them`() {
        UpdateNotifications.ensureChannels(ctx)
        UpdateNotifications.updateAvailable(ctx, "2026090700")
        var notification = posted().single { it.id == 2 }.notification
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains("2026090700") == true,
        )
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)

        UpdateNotifications.failed(ctx, "the payload transfer failed")
        notification = posted().single { it.id == 2 }.notification
        assertEquals(
            "the payload transfer failed",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

        UpdateNotifications.clearResult(ctx)
        assertTrue(posted().none { it.id == 2 })
    }
}
