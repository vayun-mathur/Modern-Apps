package com.vayunmathur.camera.ui

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator journeys through the capture modes: each mode switch rebinds a
 * CameraX session on the emulator's virtual camera, which is how the
 * session/capture code paths get exercised end to end. UiAutomator text
 * polling only (compose-test Espresso sync is broken on current Android).
 */
@RunWith(AndroidJUnit4::class)
class CameraJourneysTest {

    private fun launch(device: UiDevice): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName
        grantAll(instrumentation.uiAutomation, targetContext.packageManager, packageName)
        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)
        assertNotNull(
            "photo mode never appeared",
            device.wait(Until.findObject(By.text("Photo")), TIMEOUT_MS),
        )
        return packageName
    }

    private fun tapText(device: UiDevice, text: String): Boolean {
        val node = device.wait(Until.findObject(By.text(text)), TIMEOUT_MS) ?: return false
        node.click()
        return true
    }

    private fun tapShutter(device: UiDevice) {
        // The shutter sits centred in the bottom control cluster, above
        // the mode strip; probe the two likely rows.
        device.click(device.displayWidth / 2, (device.displayHeight * 0.82).toInt())
    }

    @Test fun modeJourneyAcrossPhotoFamily() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val pkg = launch(device)
        tapText(device, "Portrait")
        device.wait(Until.findObject(By.text("Portrait")), TIMEOUT_MS)
        tapText(device, "Sphere")
        tapText(device, "Panorama")
        tapText(device, "Photo")
        assertNotNull(device.wait(Until.findObject(By.text("Photo")), TIMEOUT_MS))
        assertEquals(pkg, device.currentPackageName)
    }

    @Test fun photoCaptureJourney() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val pkg = launch(device)
        tapShutter(device)
        // The viewfinder stays up after a capture (save is async).
        assertNotNull(device.wait(Until.findObject(By.text("Photo")), TIMEOUT_MS))
        assertEquals(pkg, device.currentPackageName)
    }

    @Test fun videoRecordJourney() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val pkg = launch(device)
        val picker = device.wait(Until.findObject(By.desc("Video")), TIMEOUT_MS)
        assertNotNull("video picker missing", picker)
        picker!!.click()
        assertNotNull(device.wait(Until.findObject(By.text("Video")), TIMEOUT_MS))
        tapText(device, "Timelapse")
        tapText(device, "Cinematic")
        tapText(device, "Video")
        tapShutter(device)
        Thread.sleep(2_000)
        tapShutter(device)
        assertNotNull(device.wait(Until.findObject(By.text("Video")), TIMEOUT_MS))
        assertEquals(pkg, device.currentPackageName)
    }

    private fun grantAll(uiAutomation: UiAutomation, packageManager: PackageManager, packageName: String) {
        val info = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            throw AssertionError("package $packageName not installed", e)
        }
        for (permission in info.requestedPermissions.orEmpty()) {
            try {
                uiAutomation.executeShellCommand("pm grant $packageName $permission").close()
            } catch (ignored: SecurityException) {
            } catch (ignored: IOException) {
            }
        }
    }

    private companion object {
        private const val TIMEOUT_MS = 15_000L
    }
}
