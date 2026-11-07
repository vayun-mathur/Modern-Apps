package com.vayunmathur.launcher

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device test for [MainActivity]'s widget-configure callback: an
 * unknown widget id cannot start a configure activity, so the failure
 * callback must fire. The bind/wallpaper/uninstall bridge paths launch
 * external system surfaces on the shared emulator and proved flaky there;
 * they are covered deterministically by MainActivityRoboTest instead.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityBridgeDeviceTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun backToLauncher() {
        // Give the launched surface a moment to come up, then leave it.
        device.waitForIdle(2_000)
        device.pressBack()
        device.waitForIdle(2_000)
    }

    @Test
    fun configureForBogusWidgetFailsBackToCaller() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val latch = CountDownLatch(1)
            var result: Boolean? = null
            scenario.onActivity { activity ->
                activity.startWidgetConfigure(987654) { configured ->
                    result = configured
                    latch.countDown()
                }
            }
            // An unknown widget id cannot start a configure activity, so the
            // failure callback fires (possibly after a transient system UI).
            if (!latch.await(3, TimeUnit.SECONDS)) backToLauncher()
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertEquals(false, result)
        }
    }

}
