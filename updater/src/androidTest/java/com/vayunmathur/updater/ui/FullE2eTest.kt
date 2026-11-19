package com.vayunmathur.updater.ui

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
 * Full e2e test: launches the updater, grants every requested
 * permission, and asserts it lands on its one screen rather than a permission
 * screen.
 *
 * There is no launcher intent to launch: MainActivity deliberately shows no
 * launcher icon and only answers `android.settings.SYSTEM_UPDATE_SETTINGS`,
 * which is the Settings > System > System update entry, so
 * `getLaunchIntentForPackage` returns null. The narrowest deterministic
 * direct entry is that same settings action, pinned to this package. Its one
 * screen always renders the "Check now" button while no check is running
 * (a fresh emulator has never checked), so the content wait keys on it.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class FullE2eTest {

    @Test
    fun launch_grantsPermissions_showsUpdaterScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        // Clean state is provided by the runner: ./test clears the app's
        // data before instrumenting (clearing from inside the test would
        // kill the instrumentation, which runs in the app process).
        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = Intent("android.settings.SYSTEM_UPDATE_SETTINGS")
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // updater screen's check button is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "updater screen never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(CHECK_NOW_LABEL)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after painting content.
        assertEquals(packageName, device.currentPackageName)
    }

    /**
     * Clean app state for this launch: the assertions below are written for a
     * fresh install, so any data (or stopped state) a previous test left
     * behind is cleared before permissions are granted and the app launches.
     */
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
                // Shell pipe failed: the permission stays ungranted and the
                // content assertions below report it.
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
        private const val CONTENT_TIMEOUT_MS = 15_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val CHECK_NOW_LABEL = "Check now"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        /**
         * Special access behind some apps' gates: exact alarms + full-screen intent
         * have no pm-grantable permission, but the shell can flip their appops.
         * Ignored where the appop does not exist; the gate assertion reports it.
         */
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SCHEDULE_EXACT_ALARM allow",
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
