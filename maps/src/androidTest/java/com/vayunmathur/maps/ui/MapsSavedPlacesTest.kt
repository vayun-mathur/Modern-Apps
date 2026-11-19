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
 * Saved-places journey: through the download + location gates onto the
 * map, into settings, then the saved-places page where a named list is
 * created and verified.
 *
 * List creation is idempotent: [LIST_NAME] already existing is a no-op
 * in the view model, so reruns still see the list.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects
 * on hidden InputManager APIs that no longer exist on current releases.
 */
@RunWith(AndroidJUnit4::class)
class MapsSavedPlacesTest {

    @Test
    fun savedPlaces_createList_listVisible() {
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

        // Map overflow gear opens settings; the saved-places row opens it.
        val gear = device.wait(Until.findObject(By.desc(SETTINGS_DESC)), NAV_TIMEOUT_MS)
        assertNotNull("settings gear never appeared", gear)
        gear!!.click()
        assertNotNull(
            "settings never opened",
            device.wait(Until.findObject(By.text(MAP_THEME_ROW)), NAV_TIMEOUT_MS),
        )
        val savedRow = device.wait(Until.findObject(By.text(SAVED_PLACES_TITLE)), NAV_TIMEOUT_MS)
        assertNotNull("saved-places row never appeared", savedRow)
        savedRow!!.click()

        // Saved-places page: the named-lists section with its entry point.
        assertNotNull(
            "saved-places page never opened",
            device.wait(Until.findObject(By.text(LISTS_HEADER)), NAV_TIMEOUT_MS),
        )
        val newList = device.wait(Until.findObject(By.text(NEW_LIST_ROW)), NAV_TIMEOUT_MS)
        assertNotNull("new-list row never appeared", newList)
        newList!!.click()
        val nameField = device.wait(
            Until.findObject(By.clazz("android.widget.EditText")),
            NAV_TIMEOUT_MS,
        )
        assertNotNull("new-list dialog never opened", nameField)
        nameField!!.setText(LIST_NAME)
        val create = device.wait(Until.findObject(By.text(CREATE_BUTTON)), NAV_TIMEOUT_MS)
        assertNotNull("create button never appeared", create)
        create!!.click()
        assertNotNull(
            "created list never appeared",
            device.wait(Until.findObject(By.text(LIST_NAME)), NAV_TIMEOUT_MS),
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
        private const val MAP_THEME_ROW = "Map theme"
        private const val SAVED_PLACES_TITLE = "Saved places"
        private const val LISTS_HEADER = "Lists"
        private const val NEW_LIST_ROW = "New list"
        private const val CREATE_BUTTON = "Create"
        private const val LIST_NAME = "JourneyList"
        private const val DOWNLOAD_GATE_TITLE = "Initializing System"
        private const val LOCATION_GATE_TITLE = "Grant Location Permission"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
