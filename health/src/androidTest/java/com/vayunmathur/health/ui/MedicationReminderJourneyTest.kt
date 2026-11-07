package com.vayunmathur.health.ui

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
 * Enables dose reminders on the fixture medication through the UI.
 *
 * Saving a schedule arms real alarms through DoseScheduler in the app
 * process, a path Robolectric can only reach with shadow alarms.
 */
@RunWith(AndroidJUnit4::class)
class MedicationReminderJourneyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName: String = targetContext.packageName

    @Test
    fun medicationWithReminders_saves() {
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        assertNotNull("today never appeared", device.wait(Until.findObject(By.text("Quick stats")), 15_000L))

        val medical = device.wait(Until.findObject(By.text("Medical")), 5_000L)
        assertNotNull("medical tab not found", medical)
        medical!!.click()
        val section = device.wait(Until.findObject(By.text("Medications")), 10_000L)
        assertNotNull("medications section never appeared", section)
        section!!.click()

        // The fixture medication saved by FixtureJourneyTest is listed;
        // open it for editing.
        var medRow = device.wait(Until.findObject(By.textStartsWith("fixturecillin")), 5_000L)
        if (medRow == null) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            medRow = device.wait(Until.findObject(By.textStartsWith("fixturecillin")), 10_000L)
        }
        assertNotNull("fixture medication row not found (FixtureJourneyTest must run first)", medRow)
        medRow!!.click()

        // Scroll the form to the reminders section and turn it on.
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        val remind = device.wait(Until.findObject(By.textContains("Remind me")), 10_000L)
        assertNotNull("reminders section never appeared", remind)
        // The switch sits at the end of the same row; tap the row's right edge.
        val bounds = remind!!.visibleBounds
        device.click(device.displayWidth - 60, bounds.centerY())
        Thread.sleep(500)

        val save = device.wait(Until.findObject(By.text("Save")), 10_000L)
        assertNotNull("save not found", save)
        save!!.click()
        assertNotNull(
            "did not return to medical page",
            device.wait(Until.findObject(By.text("Medications")), 10_000L),
        )
        assertEquals(packageName, device.currentPackageName)
    }
}
