package com.vayunmathur.games.logicgate.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journey test: open the first level from the map, tap NAND in the chip tray, see the circuit
 * cost go up by one gate, then undo it back down.
 *
 * The board is a single canvas with no per-gate semantics, so the cost readout in the test
 * panel ("N NAND") is what proves a gate landed. The level brief opens on a first visit and is
 * dismissed if present.
 */
@RunWith(AndroidJUnit4::class)
class GamePlayTest {

    @Test
    fun play_openNot_placeNand_undoRemovesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        val level = device.wait(Until.findObject(By.text(FIRST_LEVEL)), TIMEOUT_MS)
        assertNotNull("level map never showed the first level", level)
        level!!.click()

        device.wait(Until.findObject(By.text(START_BUILDING)), BRIEF_TIMEOUT_MS)?.click()
        assertNotNull("editor never appeared", device.wait(Until.findObject(By.desc(UNDO)), TIMEOUT_MS))

        // Undo any draft left by an earlier run so the count starts from an empty board.
        while (device.findObject(cost(0)) == null) {
            val undo = device.findObject(By.desc(UNDO)) ?: break
            if (!undo.isEnabled) break
            undo.click()
            device.waitForIdle()
        }

        val before = costShown(device)
        val nand = device.wait(Until.findObject(By.descStartsWith(NAND_CARD)), TIMEOUT_MS)
        assertNotNull("chip tray has no NAND", nand)
        nand!!.click()
        assertNotNull(
            "placing a NAND did not raise the cost",
            device.wait(Until.findObject(cost(before + 1)), TIMEOUT_MS),
        )

        device.findObject(By.desc(UNDO)).click()
        assertNotNull(
            "undo did not remove the NAND",
            device.wait(Until.findObject(cost(before)), TIMEOUT_MS),
        )
        assertEquals(packageName, device.currentPackageName)
    }

    /** The test panel header merges its texts, so match the cost anywhere inside it. */
    private fun cost(n: Int) = By.text(Pattern.compile("(.*\\D)?$n NAND(\\D.*)?", Pattern.DOTALL))

    private fun costShown(device: UiDevice): Int {
        val label = device.wait(Until.findObject(By.text(COST_PATTERN)), TIMEOUT_MS)
        assertNotNull("no cost readout in the test panel", label)
        val match = COST_PATTERN.matcher(label!!.text)
        return if (match.matches()) match.group(1)!!.toInt() else error("unreadable cost: ${label.text}")
    }

    private companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val BRIEF_TIMEOUT_MS = 3_000L
        private const val FIRST_LEVEL = "NOT"
        private const val START_BUILDING = "Start building"
        private const val UNDO = "Undo"
        private const val NAND_CARD = "NAND, costs"
        private val COST_PATTERN: Pattern = Pattern.compile("(?:.*\\D)?(\\d+) NAND(?:\\D.*)?", Pattern.DOTALL)
    }
}
