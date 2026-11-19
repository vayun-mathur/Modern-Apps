package com.vayunmathur.networklocation.ui

import android.app.UiAutomation
import android.content.ComponentName
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
 * Launch-no-crash test: launches the app, grants every requested permission, and
 * asserts it lands on real content rather than sitting on a permission screen.
 *
 * This app has no launcher intent by design: it is a headless system provider
 * and its only activity (MainActivity, the offline-database status screen) is
 * reached from Settings -> Location -> Location services and is guarded by
 * android.permission.INSTALL_LOCATION_PROVIDER. The narrowest deterministic
 * entry is therefore an explicit component intent to that activity, started
 * from this instrumentation (which runs in the app's own process/uid). On a
 * fresh install the offline databases are absent, so Navigation starts on the
 * Setup screen; both Setup and Status use the same "Network Location" title,
 * so the content wait keys on it.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class LaunchNoCrashTest {

    @Test
    fun launch_grantsPermissions_showsStatusScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        // Clean-state start: wipe any data a previous run left behind so the
        // first screen below is the fresh-install one, then grant permissions
        // (grants are applied after the clear so they survive it).
        clearAppData(instrumentation.uiAutomation, packageName)
        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        // No launcher intent exists (see class doc): launch MainActivity directly.
        val intent = Intent()
            .setComponent(ComponentName(packageName, "$packageName.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears once the
        // manifest's location permissions have been granted above.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        // Main content is actually visible: the status/setup screen title.
        assertNotNull(
            "status screen never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(STATUS_TITLE)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after painting content.
        assertEquals(packageName, device.currentPackageName)
    }

    private fun clearAppData(uiAutomation: UiAutomation, packageName: String) {
        try {
            uiAutomation.executeShellCommand("pm clear $packageName").close()
        } catch (ignored: SecurityException) {
            // Clear refused: leftover state may change the first screen and
            // the content assertions below report it.
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
                // Not a headlessly grantable permission (e.g. the signature/
                // privileged provider permissions in this manifest): nothing
                // to do; the content assertions below report any real gate.
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
        private const val STATUS_TITLE = "Network Location"
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
