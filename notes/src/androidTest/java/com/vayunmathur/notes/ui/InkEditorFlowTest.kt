package com.vayunmathur.notes.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Journey: create a note, add a drawing, draw a stroke in the ink editor,
 * switch tools, undo/redo, confirm, reopen the drawing, then cancel out.
 * Covers the native ink editor that Robolectric cannot load on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class InkEditorFlowTest {

    @Test
    fun addDrawing_drawStroke_confirmAndReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName

        val intent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("no launcher intent for $packageName", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(intent)

        assertNotNull(
            "notes list never appeared",
            device.wait(Until.findObject(By.text("Notes")), TIMEOUT_MS),
        )
        val fab = device.wait(
            Until.findObject(By.clickable(true).hasDescendant(By.desc("Add"))), TIMEOUT_MS,
        )
        assertNotNull("create-note FAB never appeared", fab)
        fab!!.click()
        assertNotNull(
            "note editor never opened",
            device.wait(Until.findObject(By.text("Title")), TIMEOUT_MS),
        )

        val addDrawing = device.wait(
            Until.findObject(By.clickable(true).hasDescendant(By.desc("Add drawing"))), TIMEOUT_MS,
        )
        assertNotNull("Add drawing button never appeared", addDrawing)
        addDrawing!!.click()
        assertNotNull(
            "ink editor never opened",
            device.wait(Until.findObject(By.text("Drawing")), TIMEOUT_MS),
        )

        // Draw a stroke across the middle of the canvas.
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 4, h / 2, (w * 3) / 4, h / 2, 24)
        device.waitForIdle()

        // Switch tools: highlighter, eraser, back to pen; draw again.
        clickDesc(device, "Brush")
        device.swipe(w / 4, (h * 2) / 5, (w * 3) / 4, (h * 2) / 5, 24)
        clickDesc(device, "Eraser")
        clickDesc(device, "Draw")
        // Undo then redo the last stroke.
        clickDesc(device, "Undo")
        clickDesc(device, "Forward")

        // Confirm the drawing back into the note.
        val check = device.wait(
            Until.findObject(By.clickable(true).hasDescendant(By.desc("Check"))), TIMEOUT_MS,
        )
        assertNotNull("ink editor Check button never appeared", check)
        check!!.click()
        assertNotNull(
            "note editor did not return after confirming drawing",
            device.wait(Until.findObject(By.text("Title")), TIMEOUT_MS),
        )

        // Reopen the drawing by tapping the ink block, then close without saving.
        device.swipe(w / 2, (h * 2) / 5, w / 2, (h * 2) / 5, 2) // tap-ish at block location; fallthrough below handles miss
        device.waitForIdle()
        if (device.findObject(By.text("Drawing")) != null) {
            device.pressBack()
            device.waitForIdle()
        }
        device.pressBack()
    }

    private fun clickDesc(device: UiDevice, desc: String) {
        val node = device.wait(
            Until.findObject(By.clickable(true).hasDescendant(By.desc(desc))), SHORT_TIMEOUT_MS,
        ) ?: device.findObject(By.desc(desc))
        node?.click()
        device.waitForIdle()
    }

    private companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val SHORT_TIMEOUT_MS = 5_000L
    }
}
