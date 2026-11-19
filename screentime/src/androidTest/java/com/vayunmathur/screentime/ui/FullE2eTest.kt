package com.vayunmathur.screentime.ui

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
 * Full e2e test: starts from the runner-cleared clean state, grants every requested permission
 * plus the usage-access appop, and asserts it lands on the usage dashboard.
 *
 * Screen Time has no launcher entry by design (it is reached from
 * Settings > Digital Wellbeing; see the manifest), so
 * [getLaunchIntentForPackage] cannot resolve it. The exported navigation host
 * is launched explicitly instead, which lands on the same default route (the
 * dashboard) that the Settings entry opens.
 *
 * The dashboard only shows real usage once the GET_USAGE_STATS appop is
 * allowed; without it the first screen is a "Usage access is off" prompt
 * instead of the totals card. The appop has no pm-grantable permission, so
 * the grant pass flips it through `appops set` before launching.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class FullE2eTest {

    @Test
    fun launch_grantsPermissions_showsUsageDashboard() {
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

        // Past any permission wall: the shared gate title never appears and
        // the totals card is showing (which only renders with usage access).
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "screen-time totals never appeared; usage access may be denied or the app crashed on launch",
            device.wait(Until.findObject(By.text(TOTAL_LABEL)), CONTENT_TIMEOUT_MS),
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
        private const val TOTAL_LABEL = "Total screen time"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"

        /** The explicit host component: this app ships no launcher activity. */
        private const val MAIN_ACTIVITY_CLASS = "com.vayunmathur.screentime.MainActivity"

        /**
         * Special access behind this dashboard: usage stats (the totals card
         * renders only when the op is allowed) and exact alarms (the
         * wind-down schedule arms window edges) have no pm-grantable
         * permission, but the shell can flip their appops. Ignored where the
         * appop does not exist; the content assertion reports it.
         */
        private val SPECIAL_ACCESS_COMMANDS = arrayOf(
            "appops set %s GET_USAGE_STATS allow",
            "appops set %s SCHEDULE_EXACT_ALARM allow",
        )
    }
}
