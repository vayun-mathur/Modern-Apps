package com.vayunmathur.findfamily

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.platform.FakeAndroidKeyStore
import com.vayunmathur.findfamily.notifications.EXTRA_NOSHOW_USER_ID
import com.vayunmathur.findfamily.notifications.EXTRA_NOSHOW_WAYPOINT_ID
import com.vayunmathur.library.util.DatabaseHelper
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [MainActivity] launched for real: intent parsing (invite deep links,
 * no-show targets, UWB peer), the permission gate with every permission
 * granted so `Content()` reaches [Navigation], and warm-start
 * [MainActivity.onNewIntent] delivery.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityRealLaunchTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setup() {
        FakeAndroidKeyStore.install()
        runCatching { DatabaseHelper(app).storePassphrase("activity-test-pass") }
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        // The activity's ViewModel factory reads the process singleton; point
        // it at in-memory Room so the whole graph runs on genuine rows.
        TestFindFamilyRepository.install(app)
        Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            android.Manifest.permission.BLUETOOTH_SCAN,
        )
    }

    private fun launch(intent: Intent): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create().start().resume()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(250)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    @Test fun plainLaunchReachesNavigation() {
        val controller = launch(Intent(app, MainActivity::class.java))
        assertNotNull(controller.get())
        assertEquals(app.packageName, controller.get().componentName?.packageName)
        controller.pause().stop().destroy()
    }

    @Test fun inviteDeepLinkLaunchParsesInvite() {
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("findfamily://add/CD?k=abc-DEF_123")
        }
        val controller = launch(intent)
        assertNotNull(controller.get().intent?.data)
        controller.pause().stop().destroy()
    }

    @Test fun malformedDeepLinksAreIgnored() {
        for (uri in listOf(
            "findfamily://add/",
            "findfamily://add/123",
            "findfamily://other/CD",
            "https://example.com/add/CD",
            "findfamily://add/CD?k=",
        )) {
            val intent = Intent(app, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(uri)
            }
            val controller = launch(intent)
            assertNotNull(controller.get())
            controller.pause().stop().destroy()
        }
    }

    @Test fun noShowAndUwbExtrasLaunch() {
        val intent = Intent(app, MainActivity::class.java).apply {
            putExtra(EXTRA_NOSHOW_USER_ID, 42L)
            putExtra(EXTRA_NOSHOW_WAYPOINT_ID, 7L)
            putExtra(MainActivity.EXTRA_UWB_PEER_ID, 42L)
        }
        val controller = launch(intent)
        assertEquals(42L, controller.get().intent.getLongExtra(EXTRA_NOSHOW_USER_ID, -1L))
        controller.pause().stop().destroy()
    }

    @Test fun warmStartDeliversInviteAndNoShowViaOnNewIntent() {
        val controller = launch(Intent(app, MainActivity::class.java))
        val activity = controller.get()
        val invite = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("findfamily://add/EF?k=fp1")
        }
        controller.newIntent(invite)
        assertEquals(invite.data, activity.intent.data)
        val noShow = Intent(app, MainActivity::class.java).apply {
            putExtra(EXTRA_NOSHOW_USER_ID, 9L)
        }
        controller.newIntent(noShow)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.intent.hasExtra(EXTRA_NOSHOW_USER_ID))
        controller.pause().stop().destroy()
    }
}
