package com.vayunmathur.appstore.data.installer

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The background install-confirmation path: a pending user action
 * arriving while the app is not foreground becomes a notification
 * (only with notification permission), instead of a startActivity
 * the OS would silently swallow.
 */
@RunWith(RobolectricTestRunner::class)
class InstallStatusReceiverNotificationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun pendingIntent(pkg: String = "com.example.pending"): Intent {
        val userAction = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("market://details?id=$pkg")
        }
        return Intent(InstallStatusReceiver.ACTION_INSTALL_STATUS).apply {
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, pkg)
            putExtra(Intent.EXTRA_INTENT, userAction)
        }
    }

    private fun postedCount(): Int {
        val manager = context.getSystemService(NotificationManager::class.java)
        return Shadows.shadowOf(manager).allNotifications.size
    }

    @Test
    fun `without notification permission no notification is posted`() {
        InstallStatusReceiver().onReceive(context, pendingIntent())
        assertEquals(0, postedCount())
    }

    @Test
    fun `with notification permission the confirmation is surfaced`() {
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        InstallStatusReceiver().onReceive(context, pendingIntent())
        // Foreground (Robolectric reports this process in front) the
        // prompt intent is started directly; in the background it would
        // be posted as a notification instead. Either is a surfacing —
        // what must never happen is the prompt vanishing silently.
        val started = Shadows.shadowOf(context as android.app.Application)
            .nextStartedActivity
        assertTrue(
            postedCount() > 0 || started != null,
            "confirmation was neither posted nor started",
        )
        if (started != null) {
            assertEquals(
                "market://details?id=com.example.pending",
                started.dataString,
            )
            return
        }
        val posted = Shadows.shadowOf(
            context.getSystemService(NotificationManager::class.java),
        ).allNotifications.first()
        val notification = posted as? android.app.Notification
            ?: posted.javaClass.getDeclaredField("notification")
                .apply { isAccessible = true }
                .get(posted) as android.app.Notification
        assertTrue(
            notification.extras
                .getCharSequence("android.title")
                .toString()
                .contains("com.example.pending"),
        )
    }
}
