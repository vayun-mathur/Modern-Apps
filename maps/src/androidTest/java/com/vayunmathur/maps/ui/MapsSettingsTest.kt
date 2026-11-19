package com.vayunmathur.maps.ui

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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings journey: through the download + location gates onto the map,
 * open settings from the map overflow gear, and see the settings rows.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects
 * on hidden InputManager APIs that no longer exist on current releases.
 */
@RunWith(AndroidJUnit4::class)
class MapsSettingsTest {

    @Test
    fun settings_openFromMap_settingsRowsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(
            instrumentation.uiAutomation,
            targetContext.packageManager,
            packageName,
        )

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Outer download gate first, then the location gate never appears
        // and the map search pill shows.
        device.wait(Until.gone(By.text(DOWNLOAD_GATE_TITLE)), CONTENT_TIMEOUT_MS)
        assertNull(
            "location gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(LOCATION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        val pill = device.wait(Until.findObject(By.text(SEARCH_PILL)), CONTENT_TIMEOUT_MS)
        assertNotNull("map search pill never appeared", pill)

        // The overflow gear opens settings with its display section.
        val gear = device.wait(Until.findObject(By.desc(SETTINGS_DESC)), NAV_TIMEOUT_MS)
        assertNotNull("settings gear never appeared", gear)
        gear!!.click()
        assertNotNull(
            "settings never opened",
            device.wait(Until.findObject(By.text(DISPLAY_SECTION)), NAV_TIMEOUT_MS),
        )
        assertNotNull(
            "map-theme row never appeared",
            device.wait(Until.findObject(By.text(MAP_THEME_ROW)), NAV_TIMEOUT_MS),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    private fun grantAllRequestedPermissions(
        uiAutomation: UiAutomation,
        packageManager: PackageManager,
        packageName: String,
    ) {
        val info = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            throw AssertionError("package $packageName not installed", e)
        }
        for (permission in info.requestedPermissions.orEmpty()) {
            try {
                uiAutomation.executeShellCommand("pm grant $packageName $permission").close()
            } catch (ignored: SecurityException) {
                // Not a headlessly grantable permission: nothing to do.
            } catch (ignored: IOException) {
                // Shell pipe failed: the assertions below report it.
            }
        }
        for (command in SPECIAL_ACCESS_COMMANDS) {
            try {
                uiAutomation.executeShellCommand(command.format(packageName)).close()
            } catch (ignored: SecurityException) {
                // Appop missing on this release: the gate assertion reports it.
            } catch (ignored: IOException) {
                // Shell pipe failed: the gate assertion below reports it.
            }
        }
    }

    private companion object {
        private const val CONTENT_TIMEOUT_MS = 300_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val NAV_TIMEOUT_MS = 30_000L
        private const val SEARCH_PILL = "Search."
        private const val SETTINGS_DESC = "Settings"
        private const val DISPLAY_SECTION = "Display"
        private const val MAP_THEME_ROW = "Map theme"
        private const val DOWNLOAD_GATE_TITLE = "Initializing System"
        private const val LOCATION_GATE_TITLE = "Grant Location Permission"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
