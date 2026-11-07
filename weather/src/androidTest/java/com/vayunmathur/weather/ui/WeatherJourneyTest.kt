package com.vayunmathur.weather.ui

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
 * On-device journey that needs the real stack Robolectric cannot provide:
 * the bundled places catalogue unpacks (GeoDatabase), city search returns a
 * real row, and picking it inserts the location through the real
 * SQLCipher-backed repository and returns home.
 */
@RunWith(AndroidJUnit4::class)
class WeatherJourneyTest {

    @Test
    fun searchBerlin_pickResult_returnsHomeWithLocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAll(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        assertNotNull(
            "empty state never appeared",
            device.wait(Until.findObject(By.text("No locations yet")), 20_000),
        )
        device.wait(Until.findObject(By.text("Add a location")), 10_000)!!.click()
        assertNotNull(
            "search never opened",
            device.wait(Until.findObject(By.text("City name")), 15_000),
        )
        val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), 10_000)
        val field = fields!!.first()
        field.click()
        field.setText("Berlin")
        // Real catalogue search: a Berlin result row appears (name + region).
        val result = device.wait(Until.findObject(By.text("Berlin")), 30_000)
        assertNotNull("Berlin result never appeared (places catalogue search failed)", result)
        result!!.click()
        // Back home with Berlin as the active location in the search bar.
        assertNotNull(
            "home did not show Berlin after picking it",
            device.wait(Until.findObject(By.textContains("Berlin")), 20_000),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    private fun grantAll(uiAutomation: UiAutomation, pm: PackageManager, pkg: String) {
        val info = try {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            throw AssertionError("package $pkg not installed", e)
        }
        for (permission in info.requestedPermissions.orEmpty()) {
            try {
                uiAutomation.executeShellCommand("pm grant $pkg $permission").close()
            } catch (ignored: SecurityException) {
            } catch (ignored: IOException) {
            }
        }
    }
}
