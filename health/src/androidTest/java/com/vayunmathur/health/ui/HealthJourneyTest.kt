package com.vayunmathur.health.ui

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tab journey and dose-reminder journey on a real device.
 *
 * Taps each bottom tab so every Navigation entry composes at least once,
 * then opens the dose reminder activity directly (the way a dose alarm
 * does) and checks its actions are on screen. UiDevice polling only:
 * the Compose test rule cannot run on-device in this repo.
 */
@RunWith(AndroidJUnit4::class)
class HealthJourneyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName: String = targetContext.packageName

    private fun launchApp() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        assertNotNull(
            "today content never appeared",
            device.wait(Until.findObject(By.text("Quick stats")), 15_000L),
        )
    }

    private fun tapTab(label: String, marker: String) {
        val tab = device.wait(Until.findObject(By.text(label)), 5_000L)
        assertNotNull("tab $label not found", tab)
        tab!!.click()
        assertNotNull(
            "marker $marker never appeared after tapping $label",
            device.wait(Until.findObject(By.text(marker)), 10_000L),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    @Test
    fun tabs_eachTabComposes() {
        launchApp()
        tapTab("Body", "Body Composition")
        tapTab("Medical", "Vaccinations")
        tapTab("Nutrition", "Protein")
        tapTab("Today", "Quick stats")
    }

    @Test
    fun doseReminder_showsTakenAndSnooze() {
        val intent = Intent().apply {
            component = ComponentName(packageName, "com.vayunmathur.health.platform.DoseReminderActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("SCHEDULE_ID", "journey-sched")
            putExtra("MEDICATION_ID", "journey-med")
        }
        targetContext.startActivity(intent)
        assertNotNull(
            "Taken action never appeared on the dose reminder",
            device.wait(Until.findObject(By.text("Taken")), 10_000L),
        )
        assertNotNull(
            "Snooze action never appeared on the dose reminder",
            device.wait(Until.findObject(By.text("Snooze")), 5_000L),
        )
        assertEquals(packageName, device.currentPackageName)
        device.pressBack()
    }
}
