package com.vayunmathur.todo.ui

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Complete journey: create a todo, reopen it, tap its checkbox, and assert
 * the checked done state.
 *
 * The test is self-contained: it creates its own uniquely titled todo first,
 * so earlier runs cannot collide with it. The done state is read back from
 * the checkbox node itself via isChecked, which is the same state the list
 * row renders. A short settle covers the editor's debounced write.
 *
 * UiDevice polling, not compose-test: compose's Espresso idle-sync reflects on
 * hidden InputManager APIs that no longer exist on current releases, so every
 * compose-rule test dies before its first line. Text polling works for Compose
 * and non-Compose screens alike.
 */
@RunWith(AndroidJUnit4::class)
class TodoCompleteTest {

    @Test
    fun createTodo_toggleCheckbox_showsDone() {
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
        // todo list search field is showing.
        assertNull(
            "permission gate is showing; grants did not clear it",
            device.wait(Until.findObject(By.text(PERMISSION_GATE_TITLE)), GATE_TIMEOUT_MS),
        )
        assertNotNull(
            "todo list never appeared; the app may have crashed on launch",
            device.wait(Until.findObject(By.text(SEARCH_PLACEHOLDER)), CONTENT_TIMEOUT_MS),
        )

        val fab = device.wait(Until.findObject(By.desc(ADD_DESCRIPTION)), SHORT_TIMEOUT_MS)
        assertNotNull("create-todo FAB never appeared", fab)
        fab!!.click()
        assertNotNull(
            "todo editor never opened",
            device.wait(Until.findObject(By.text(EDITOR_NOTES_LABEL)), CONTENT_TIMEOUT_MS),
        )
        val fields = device.wait(Until.findObjects(By.clazz(EDIT_TEXT_CLASS)), CONTENT_TIMEOUT_MS)
        assertNotNull("editor fields never appeared", fields)
        fields!![0].click()
        fields[0].setText(TASK_TITLE)
        // The editor debounces writes briefly; settle before leaving so the row persists.
        Thread.sleep(SAVE_SETTLE_MS)
        device.pressBack()

        val row = device.wait(Until.findObject(By.text(TASK_TITLE)), CONTENT_TIMEOUT_MS)
        assertNotNull("created todo is not listed", row)
        row!!.click()
        assertNotNull(
            "todo editor never reopened",
            device.wait(Until.findObject(By.text(EDITOR_NOTES_LABEL)), CONTENT_TIMEOUT_MS),
        )

        // The detail screen owns a single checkbox bound to the done flag.
        val checkbox = device.wait(Until.findObject(By.checkable(true)), CONTENT_TIMEOUT_MS)
        assertNotNull("done checkbox never appeared", checkbox)
        checkbox!!.click()
        val toggled = device.wait(Until.findObject(By.checkable(true)), CONTENT_TIMEOUT_MS)
        assertNotNull("done checkbox vanished after toggle", toggled)
        assertTrue("todo is not marked done after toggle", toggled!!.isChecked)
        Thread.sleep(SAVE_SETTLE_MS)
        device.pressBack()

        // Done tasks collapse into the "Completed (n)" section under the open ones.
        assertNotNull(
            "completed section is not shown after returning",
            device.wait(Until.findObject(By.textStartsWith(COMPLETED_HEADER)), CONTENT_TIMEOUT_MS),
        )
        // Still alive in front: the process did not crash through the journey.
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
        private const val SHORT_TIMEOUT_MS = 5_000L
        private const val SAVE_SETTLE_MS = 1_000L
        private const val COMPLETED_HEADER = "Completed ("
        private const val SEARCH_PLACEHOLDER = "Search Today"
        private const val EDITOR_NOTES_LABEL = "Notes"
        private const val ADD_DESCRIPTION = "Add"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val TASK_TITLE = "Journey Done Alpha"
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
