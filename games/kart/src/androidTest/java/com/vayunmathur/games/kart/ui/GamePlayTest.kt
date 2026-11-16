package com.vayunmathur.games.kart.ui

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
 * Journey test: launches kart, taps Start Race, and asserts the race HUD stays
 * put rather than dropping back to the garage or crashing.
 *
 * Kart boots into the garage lobby (Route.Lobby) with the stable Start Race
 * button; tapping it runs the 3-2-1 countdown into the placeholder race, whose
 * HUD carries the stable "Lap 1 of 3" readout for the whole first lap. The
 * wait covers the countdown plus race start.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun play_startRace_raceHudStays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        grantAllRequestedPermissions(instrumentation.uiAutomation, targetContext.packageManager, packageName)

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        // Past the permission screen: the gate wall never appears and the
        // Start Race button is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        val home = device.wait(Until.findObject(By.text(START_RACE_LABEL)), CONTENT_TIMEOUT_MS)
        assertNotNull("kart garage never appeared; the app may have crashed on launch", home)
        // Play action: start the race; the countdown runs into the race HUD.
        home!!.click()
        // Race board is showing: the lap readout paints once racing starts.
        val board = device.wait(Until.findObject(By.text(RACE_LAP_FIRST)), CONTENT_TIMEOUT_MS)
        assertNotNull("race HUD never appeared; tapping start may not have begun the race", board)
        // One interaction: tap the lap readout itself (a no-op on static HUD
        // text) and confirm the race screen is still there.
        board!!.click()
        assertNotNull(
            "race HUD left after tapping it; the app may have crashed on interaction",
            device.wait(Until.findObject(By.text(RACE_LAP_FIRST)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash after the interaction.
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
        private const val CONTENT_TIMEOUT_MS = 15_000L
        private const val GATE_TIMEOUT_MS = 5_000L
        private const val START_RACE_LABEL = "Start Race"
        private const val RACE_LAP_FIRST = "Lap 1 of 3"
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
