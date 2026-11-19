package com.vayunmathur.auto.ui

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
 * Full e2e test: starts from the runner-cleared clean state, grants every requested permission, and
 * asserts it lands on the phone-side connection status screen.
 *
 * Unlike the car display (driven through the Desktop Head Unit loop), the
 * phone side of Auto is an ordinary launcher app: MainActivity carries a
 * MAIN/LAUNCHER filter, so the platform launcher intent resolves it. Opening
 * it starts the projection service listener, but with no head unit attached
 * the first screen is simply the disconnected status card asserted below.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class FullE2eTest {

    @Test
    fun launch_grantsPermissions_showsConnectionStatus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        // Clean state is provided by the runner: ./test clears the app's
        // data before instrumenting (clearing from inside the test would
        // kill the instrumentation, which runs in the app process).

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past any permission wall: the shared gate title never appears and
        // the connection status card is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "auto status screen never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(CONNECTION_LABEL)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after painting content.
        assertEquals(packageName, device.currentPackageName)
    }


    /**
     * Starts from clean app state: wipes the target package's data (which also
     * resets its grants, so this runs before the grant pass).
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
        private const val CONNECTION_LABEL = "Connection"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        /**
         * Special access this app declares: drawing over other apps (the
         * car-mode overlays) has no pm-grantable permission, but the shell
         * can flip its appop. Ignored where the appop does not exist; the
         * content assertion reports it.
         */
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s SYSTEM_ALERT_WINDOW allow",
        )
    }
}
