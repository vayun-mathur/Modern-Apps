package com.vayunmathur.games.voxels.ui

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
 * Full e2e test: launches the app, grants every requested permission, and
 * asserts it lands on the world menu rather than sitting on a permission screen.
 *
 * Voxels launches into MenuActivity (a raw ComponentActivity; MainActivity is
 * the unexported Vulkan game behind Play). The menu's Select a world subtitle
 * is stable, while world rows carry dynamic names and timestamps. The content
 * wait is 60s: cold start provisions the sync identity and reads the world list.
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
class FullE2eTest {

    @Test
    fun launch_grantsPermissions_showsWorldMenu() {
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

        // Past the permission screen: the gate wall never appears (voxels has
        // none; gated apps assert the same absence to prove they cleared it).
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        // Main content is actually visible: the world menu subtitle.
        assertNotNull(
            "voxels world menu never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(MENU_SUBTITLE)), CONTENT_TIMEOUT_MS),
        )
        // Core interaction: open the Create New World form, verify its
        // labels, then Cancel back to the menu. Create & Play is not tapped:
        // it launches the Vulkan game, whose first frame is not a
        // deterministic emulator assertion.
        val create = device.wait(Until.findObject(By.text(CREATE_WORLD_BUTTON)), CONTENT_TIMEOUT_MS)
        assertNotNull("Create New World button never appeared", create)
        create!!.click()
        assertNotNull(
            "create-world form never appeared",
            device.wait(Until.findObject(By.text(CREATE_FORM_TITLE)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "world-name field never appeared",
            device.wait(Until.findObject(By.text(WORLD_NAME_LABEL)), CONTENT_TIMEOUT_MS),
        )
        assertNotNull(
            "game-mode picker never appeared",
            device.wait(Until.findObject(By.text(GAME_MODE_LABEL)), CONTENT_TIMEOUT_MS),
        )
        val cancel = device.wait(Until.findObject(By.text(CANCEL_LABEL)), CONTENT_TIMEOUT_MS)
        assertNotNull("Cancel button never appeared on the create form", cancel)
        cancel!!.click()
        assertNotNull(
            "world menu never reappeared after Cancel",
            device.wait(Until.findObject(By.text(MENU_SUBTITLE)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after painting content.
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
        private const val CONTENT_TIMEOUT_MS = 60_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val MENU_SUBTITLE = "Select a world"
        private const val PERMISSION_GATE_TITLE = "Grant Required Permissions"
        private const val CREATE_WORLD_BUTTON = "Create New World"
        private const val CREATE_FORM_TITLE = "Create World"
        private const val WORLD_NAME_LABEL = "World name"
        private const val GAME_MODE_LABEL = "Game mode"
        private const val CANCEL_LABEL = "Cancel"

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
