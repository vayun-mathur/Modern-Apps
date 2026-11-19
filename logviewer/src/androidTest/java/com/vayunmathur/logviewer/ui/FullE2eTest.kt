package com.vayunmathur.logviewer.ui

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
 * Launch-no-crash test: launches the viewer, grants every requested permission,
 * and asserts it lands on real content rather than a permission screen.
 *
 * There is deliberately no launcher intent to launch: both entry points are
 * permission-guarded and reached from elsewhere (Settings, the crash dialog,
 * another priv-app), so `getLaunchIntentForPackage` returns null. The
 * narrowest deterministic direct entry is the whole-device logcat action
 * (`com.vayunmathur.logviewer.LOGCAT`, see LogViewerIntents) with no extras:
 * it needs no crashed-app report payload, and the test APK is signed with the
 * app's key, which is what the SHOW_LOGCAT (signature|privileged) activity
 * permission requires of its caller. That entry renders the log under the
 * "System log" title once the `logcat -d` snapshot has loaded, so the content
 * wait keys on that title.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class LaunchNoCrashTest {

    @Test
    fun launch_grantsPermissions_showsSystemLog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        clearPackageData(instrumentation.uiAutomation, packageName)
        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = Intent(ACTION_LOGCAT)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // system log title is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "system log never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(SYSTEM_LOG_TITLE)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after painting content.
        assertEquals(packageName, device.currentPackageName)
    }

    /**
     * Clean app state for this launch: the assertions below are written for a
     * fresh install, so any data (or stopped state) a previous test left
     * behind is cleared before permissions are granted and the app launches.
     */
    private fun clearPackageData(uiAutomation: UiAutomation, packageName: String) {
        try {
            uiAutomation.executeShellCommand("pm clear $packageName").close()
        } catch (ignored: SecurityException) {
            // Cannot clear: the content assertions below report stale state.
        } catch (ignored: IOException) {
            // Shell pipe failed: the content assertions below report it.
        }
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
        private const val ACTION_LOGCAT = "com.vayunmathur.logviewer.LOGCAT"
        private const val SYSTEM_LOG_TITLE = "System log"
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
