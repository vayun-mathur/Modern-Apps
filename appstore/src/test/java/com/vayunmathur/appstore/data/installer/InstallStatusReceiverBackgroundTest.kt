package com.vayunmathur.appstore.data.installer

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The background half of InstallStatusReceiver: with this process not
 * in the foreground, a pending user action becomes a notification on
 * the installs channel carrying the package name (permission
 * permitting), and failure reasons name the OS refusal in plain text.
 */
@RunWith(RobolectricTestRunner::class)
class InstallStatusReceiverBackgroundTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        // Our own process, but not foreground: the receiver must not
        // try a background startActivity.
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.RunningAppProcessInfo().apply {
            processName = context.packageName
            pid = Process.myPid()
            uid = Process.myUid()
            importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND
        }
        Shadows.shadowOf(am).setProcesses(listOf(info))
        InstallFailureBatch.end()
    }

    private fun pendingIntent(pkg: String): Intent {
        val userAction = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://details?id=$pkg")
        }
        return Intent(InstallStatusReceiver.ACTION_INSTALL_STATUS).apply {
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, pkg)
            putExtra(Intent.EXTRA_INTENT, userAction)
        }
    }

    private fun posted() = Shadows.shadowOf(
        context.getSystemService(NotificationManager::class.java),
    ).allNotifications

    @Test
    fun `background pending action posts a confirmation notification`() {
        Shadows.shadowOf(context as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        InstallStatusReceiver().onReceive(context, pendingIntent("com.example.bg"))
        assertEquals(1, posted().size)
        val notification = posted().first()
        assertTrue(
            notification.extras.getCharSequence("android.title").toString()
                .contains("com.example.bg"),
            "title was ${notification.extras.getCharSequence("android.title")}",
        )
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("appstore-installs")
        assertNotNull(channel, "installs channel must be created before posting")
    }

    @Test
    fun `background pending action without permission posts nothing`() {
        InstallStatusReceiver().onReceive(context, pendingIntent("com.example.quiet"))
        assertTrue(posted().isEmpty())
        assertEquals(null, Shadows.shadowOf(context as Application).nextStartedActivity)
    }

    @Test
    fun `failure reasons name the refusal for each status`() {
        InstallFailureBatch.begin()
        try {
            val receiver = InstallStatusReceiver()
            fun failure(status: Int, message: String?, pkg: String) {
                val intent = Intent(InstallStatusReceiver.ACTION_INSTALL_STATUS).apply {
                    putExtra(PackageInstaller.EXTRA_STATUS, status)
                    putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, pkg)
                    putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message)
                }
                receiver.onReceive(context, intent)
            }
            failure(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null, "com.example.incompat")
            failure(PackageInstaller.STATUS_FAILURE_STORAGE, null, "com.example.storage")
            failure(PackageInstaller.STATUS_FAILURE_CONFLICT, null, "com.example.conflict")
            failure(PackageInstaller.STATUS_FAILURE, "server said no", "com.example.other")
            failure(PackageInstaller.STATUS_FAILURE, "  ", "com.example.blank")
        } finally {
            val texts = InstallFailureBatch.end()
            assertEquals(5, texts.size, texts.toString())
            assertTrue(texts[0].contains("com.example.incompat"), texts[0])
            assertTrue(texts[3].contains("server said no"), texts[3])
            // Distinct statuses must not all collapse to one reason.
            assertEquals(5, texts.toSet().size, texts.toString())
        }
    }
}
