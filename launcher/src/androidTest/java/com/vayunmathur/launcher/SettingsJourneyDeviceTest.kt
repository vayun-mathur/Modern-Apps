package com.vayunmathur.launcher

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device journey into the settings page: long-press the workspace, open
 * "Home settings", and change a grid setting there. This is the only route
 * that composes SettingsPage with the real ViewModel, which the JVM cannot
 * construct against the encrypted database.
 */
@RunWith(AndroidJUnit4::class)
class SettingsJourneyDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    @Test
    fun longPressOpensSettingsAndGridRowIsThere() {
        val targetContext = instrumentation.targetContext
        val intent = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
        assertNotNull(intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        device.waitForIdle(3_000)

        // Long-press an empty part of the workspace (centre of the screen is
        // between icons on the seeded layout).
        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, height / 2, width / 2, height / 2, 120)

        val settingsEntry = device.wait(Until.findObject(By.text("Home settings")), 10_000)
        assertNotNull("workspace options never appeared", settingsEntry)
        settingsEntry.click()

        val columns = device.wait(Until.findObject(By.text("Columns")), 10_000)
        assertNotNull("settings page never appeared", columns)
        assertNotNull(device.findObject(By.text("Home screen")))
        assertNotNull(device.findObject(By.text("Grid")))

        // Back returns to the workspace.
        device.pressBack()
        device.waitForIdle(2_000)
    }
}
