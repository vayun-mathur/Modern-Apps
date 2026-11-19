package com.vayunmathur.education.ui

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
 * Launch-no-crash test: launches the app, grants every requested permission, and
 * asserts it gets past the permission screen onto the Learn catalog.
 *
 * Education loads its bundled content packs before the catalog paints, so the
 * marker wait covers that load. A fresh install has a blank learner name, so
 * the home title reads Learn.
 *
 * Strengthened launch-e2e: clears the target package data before the
 * grant/launch pass so the journey starts from clean state, then performs
 * one small deterministic core interaction (see the test body) and returns
 * to the launch surface. Still a single @Test for this app.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class LaunchNoCrashTest {

    @Test
    fun launch_grantsPermissions_showsLearnCatalog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        // Clean state first (fresh-install journey); grants follow the clear
        // because a clear can drop runtime-grant state on some releases.
        clearAppData(instrumentation.uiAutomation, packageName)

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // Learn catalog title is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "learn catalog never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(LEARN_TITLE)), CONTENT_TIMEOUT_MS),
        )
        // Core interaction: with no PIN on a fresh install the Settings
        // action opens Parent settings directly (openParentArea); verify it,
        // then return to the catalog. Verified labels: Math is a bundled
        // subject header; Parent settings / Name are the parent surface.
        assertNotNull(
            "bundled Math subject never appeared on the catalog",
            device.wait(Until.findObject(By.text(MATH_SUBJECT)), CONTENT_TIMEOUT_MS),
        )
        val settings = device.wait(Until.findObject(By.desc(SETTINGS_DESC)), CONTENT_TIMEOUT_MS)
        assertNotNull("Settings action never appeared on the catalog", settings)
        settings!!.click()
        assertNotNull(
            "parent settings never appeared",
            device.wait(Until.findObject(By.text(PARENT_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "learner Name field never appeared in parent settings",
            device.wait(Until.findObject(By.text(NAME_LABEL)), CONTENT_TIMEOUT_MS),
        )
        device.pressBack()
        assertNotNull(
            "learn catalog never reappeared after Back",
            device.wait(Until.findObject(By.text(LEARN_TITLE)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after painting content.
        assertEquals(packageName, device.currentPackageName)
    }

    private fun clearAppData(uiAutomation: UiAutomation, packageName: String) {
        try {
            uiAutomation.executeShellCommand("pm clear $packageName").close()
        } catch (ignored: SecurityException) {
            // Clear refused: the journey assertions below still report state.
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
        private const val LEARN_TITLE = "Learn"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private const val MATH_SUBJECT = "Math"
        private const val SETTINGS_DESC = "Settings"
        private const val PARENT_TITLE = "Parent settings"
        private const val NAME_LABEL = "Name"

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
