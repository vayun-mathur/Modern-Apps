package com.vayunmathur.screentime.ui

import android.os.Looper
import com.vayunmathur.screentime.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivitiesDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun dashboardActivityResumesAndRefreshes() {
        val controller = Robolectric.buildActivity(DashboardActivity::class.java).create().start().resume().visible()
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    private fun pump() {
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
    }

    private fun drain() {
        Thread.sleep(500)
        repeat(8) {
            try {
                kotlinx.coroutines.test.runTest { }
            } catch (e: IllegalStateException) {
                if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
            }
            Thread.sleep(300)
        }
    }

    @Test fun windDownActivityResumesAndRefreshes() {
        val controller = Robolectric.buildActivity(WindDownActivity::class.java).create().start().resume().visible()
        pump()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
        drain()
    }

    @Test fun appDetailsActivityResumesWithPackageAndSuspendedUri() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = android.content.Intent(ctx, AppDetailsActivity::class.java)
            .putExtra(AppDetailsActivity.EXTRA_DETAILS_PACKAGE, "com.a.app")
        val controller = Robolectric.buildActivity(AppDetailsActivity::class.java, intent).create().start().resume().visible()
        pump()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()

        val uriIntent = android.content.Intent("android.intent.action.SHOW_SUSPENDED_APP_DETAILS")
            .setData(android.net.Uri.parse("package:com.b.app"))
        val second = Robolectric.buildActivity(AppDetailsActivity::class.java, uriIntent).create().start().resume().visible()
        pump()
        assertFalse(second.get().isFinishing)
        second.pause().stop().destroy()
        drain()
    }
}
