package com.vayunmathur.parentalcontrols.ui

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
 * Full e2e test: starts from the runner-cleared clean state, grants every requested permission, and
 * asserts it lands on the parental-controls dashboard.
 *
 * Parental Controls has no launcher entry by design (it is reached from
 * Settings > Parental controls; see the manifest), so [getLaunchIntentForPackage]
 * cannot resolve it. The exported navigation host is launched explicitly
 * instead, which lands on the same default route (the dashboard) that the
 * Settings entry opens.
 *
 * The dashboard itself is read-only and ungated: the parent PIN only guards
 * the editors behind it, so a cold start must show the dashboard, never the
 * PIN setup or PIN gate. PACKAGE_USAGE_STATS is an appop, granted headlessly
 * below alongside the exact-alarm op the bedtime schedule relies on.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class FullE2eTest {

    @Test
    fun launch_grantsPermissions_showsDashboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        // Clean state is provided by the runner: ./test clears the app's
        // data before instrumenting (clearing from inside the test would
        // kill the instrumentation, which runs in the app process).

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        // No launcher intent exists for this package (see the class doc), so
        // the exported host is addressed by component instead.
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(packageName, MAIN_ACTIVITY_CLASS))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past any permission wall: the shared gate title never appears, and
        // neither do the PIN setup or PIN gate, which guard edits, not launch.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNull(
            "PIN setup is showing on a plain launch; the dashboard should be first",
            device.wait(Until.findObject(By.text(PIN_SETUP_TITLE)), GATE_TIMEOUT_MS),
        )
        // The dashboard's master switch row is showing.
        assertNotNull(
            "parental-controls dashboard never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(CONTROLS_SWITCH_LABEL)), CONTENT_TIMEOUT_MS),
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
        private const val CONTROLS_SWITCH_LABEL = "Controls for this phone"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private const val PIN_SETUP_TITLE = "Set parent PIN"

        /** The explicit host component: this app ships no launcher activity. */
        private const val MAIN_ACTIVITY_CLASS = "com.vayunmathur.parentalcontrols.MainActivity"

        /**
         * Special access this app declares: usage stats (daily caps resume
         * mid-day after a reboot) and exact alarms (the bedtime boundary) have
         * no pm-grantable permission, but the shell can flip their appops.
         * Ignored where the appop does not exist; the content assertion
         * reports it.
         */
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s GET_USAGE_STATS allow",
            "appops set %s SCHEDULE_EXACT_ALARM allow",
        )
    }
}
