package com.vayunmathur.cast.tv.ui

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
 * Launch-no-crash test: clears the receiver's data, grants every requested
 * permission, and asserts it reaches the ready-to-receive idle screen.
 *
 * The TV receiver only advertises a LEANBACK_LAUNCHER entry, which a phone
 * launcher query does not resolve, so [getLaunchIntentForPackage] cannot be
 * used. The exported receiver host is launched explicitly instead, which is
 * also what opening the app on a TV does: it starts the receiver service and
 * settles on the ready screen once the device identity is loaded and the
 * sockets are bound. The picture's separate activity is never started here:
 * nothing is mirroring during this test.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class LaunchNoCrashTest {

    @Test
    fun launch_grantsPermissions_showsReadyToReceive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        clearAppData(instrumentation.uiAutomation, packageName)

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        // Leanback-only launcher entry (see the class doc), so the exported
        // host is addressed by component instead.
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(packageName, MAIN_ACTIVITY_CLASS))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past any permission wall: the shared gate title never appears and
        // the receiver's ready screen is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "receiver ready screen never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(READY_TITLE)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after painting content.
        assertEquals(packageName, device.currentPackageName)
    }


    /**
     * Starts from clean app state: wipes the target package's data (which also
     * resets its grants, so this runs before the grant pass).
     */
    private fun clearAppData(uiAutomation: UiAutomation, packageName: String) {
        try {
            uiAutomation.executeShellCommand("pm clear $packageName").close()
        } catch (ignored: SecurityException) {
            // Cannot clear headlessly: the content assertions below report stale state.
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
        private const val READY_TITLE = "Ready to receive"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        /** The explicit host component: this app ships no phone launcher activity. */
        private const val MAIN_ACTIVITY_CLASS = "com.vayunmathur.cast.tv.MainActivity"

        /**
         * Special access this receiver declares: full-screen intent (opening
         * the TV automatically when a cast incoming arrives) has no
         * pm-grantable permission, but the shell can flip its appop. Ignored
         * where the appop does not exist; the content assertion reports it.
         */
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s USE_FULL_SCREEN_INTENT allow",
        )
    }
}
