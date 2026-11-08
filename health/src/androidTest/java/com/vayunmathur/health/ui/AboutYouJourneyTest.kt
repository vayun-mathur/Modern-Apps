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
 * Answers the About-you smoking question through the UI on-device.
 *
 * Saving writes the profile locally and then mirrors it through
 * PersonalHealthRecords, whose client only exists in the app process;
 * the Medical page's import also runs on arrival. JVM tests can only
 * reach the mirror's unavailable early-return.
 */
@RunWith(AndroidJUnit4::class)
class AboutYouJourneyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName: String = targetContext.packageName

    @Test
    fun aboutYou_smokingAnswerSaves() {
        try {
            val info = targetContext.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            for (permission in info.requestedPermissions.orEmpty()) {
                try {
                    instrumentation.uiAutomation.executeShellCommand("pm grant $packageName $permission").close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        assertNotNull("today never appeared", device.wait(Until.findObject(By.text("Quick stats")), 15_000L))

        val medical = device.wait(Until.findObject(By.text("Medical")), 5_000L)
        assertNotNull("medical tab not found", medical)
        medical!!.click()
        val aboutYou = device.wait(Until.findObject(By.text("About you")), 10_000L)
        assertNotNull("about you section never appeared", aboutYou)
        aboutYou!!.click()

        val smoking = device.wait(Until.findObject(By.text("Do you smoke?")), 10_000L)
        assertNotNull("smoking row never appeared", smoking)
        smoking!!.click()

        val former = device.wait(Until.findObject(By.text("Former smoker")), 10_000L)
        assertNotNull("smoking dialog never appeared", former)
        former!!.click()

        // Give the save + mirror a moment to run in the app process; the
        // row's supporting-text update is pinned by the JVM dialog tests.
        Thread.sleep(2_000)
        assertEquals(packageName, device.currentPackageName)
    }
}
