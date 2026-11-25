package com.vayunmathur.backup.ui

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Core-journey e2e: drives the app's primary user flow with real
 * navigation/interaction assertions, beyond the launch-only FullE2eTest.
 * UiDevice polling, not compose-test (see FullE2eTest for why).
 * First-run core flow: onboarding -> generate the 12-word recovery code -> confirm action appears. Does not confirm/enable, so no backup destination is created.
 */
@RunWith(AndroidJUnit4::class)
class CoreJourneyTest {

    @Test
    fun generateRecoveryCode_coreJourney() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val targetContext = instrumentation.targetContext
        val packageName = targetContext.packageName
        grantAll(instrumentation.uiAutomation, targetContext.packageManager, packageName)
        launchComponent(packageName, "com.vayunmathur.backup.MainActivity", device, targetContext)
        waitText(device, "Set up Backup")
        waitText(device, "Recovery code")
        // Scroll the recovery card fully on screen first: an off-screen node's
        // centre click lands outside the window and never reaches the button.
        device.swipe(500, 1800, 500, 700, 20)
        device.swipe(500, 1800, 500, 700, 20)
        clickText(device, "Generate recovery code")
        device.swipe(500, 1800, 500, 700, 20)
        waitText(device, "I saved it — enable encrypted backups")
        assertEquals(packageName, device.currentPackageName)
    }


    fun launch(launcherPkg: String, device: UiDevice, ctx: android.content.Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(launcherPkg)
        assertNotNull("no launcher intent for $launcherPkg", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        assertGateAbsent(device)
    }
    fun launchComponent(pkg: String, cls: String, device: UiDevice, ctx: android.content.Context, action: String? = null) {
        val intent = Intent()
        if (action != null) intent.action = action
        intent.setComponent(ComponentName(pkg, cls))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        assertGateAbsent(device)
    }
    fun assertGateAbsent(device: UiDevice) {
        org.junit.Assert.assertNull("permission gate showing", device.wait(Until.findObject(By.text(GATE)), GATE_MS))
    }
    fun waitText(device: UiDevice, text: String): UiObject2 {
        val o = device.wait(Until.findObject(By.text(text)), TIMEOUT_MS)
        assertNotNull("never appeared: $text", o); return o!!
    }
    fun waitAny(device: UiDevice, vararg texts: String): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            for (t in texts) { device.findObject(By.text(t))?.let { return t } }
            Thread.sleep(250)
        }
        throw AssertionError("none appeared: ${texts.joinToString()}")
    }
    fun clickText(device: UiDevice, text: String) {
        val o = waitText(device, text)
        scrollIntoView(device, text)
        // Click the clickable ancestor of the text (repo FAB pattern), not
        // the text node's own centre, which can land on a non-clickable span.
        val clickable = device.wait(Until.findObject(By.clickable(true).hasDescendant(By.text(text))), 3000)
        if (clickable != null) clickable.click() else { val b = o.visibleBounds; device.click(b.centerX(), b.centerY()) }
        Thread.sleep(600)
    }
    fun scrollIntoView(device: UiDevice, text: String) {
        repeat(10) {
            val o = device.findObject(By.text(text)) ?: return
            val y = o.visibleBounds.centerY()
            if (y in 250 until device.displayHeight - 250) return
            if (y >= device.displayHeight - 250) device.swipe(500, 1700, 500, 700, 20)
            else device.swipe(500, 700, 500, 1700, 20)
            Thread.sleep(300)
        }
    }
    fun clickTextIfPresent(device: UiDevice, text: String): Boolean {
        val o = device.wait(Until.findObject(By.text(text)), 3000) ?: return false
        val b = o.visibleBounds; device.click(b.centerX(), b.centerY()); Thread.sleep(600); return true
    }
    fun fillField(device: UiDevice, index: Int, value: String) {
        val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), TIMEOUT_MS)
        assertNotNull("no edit fields", fields); assertTrue("missing field $index", fields!!.size > index)
        fields[index].click(); fields[index].setText(value)
    }
    fun grantAll(ua: UiAutomation, pm: PackageManager, pkg: String) {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        for (p in info.requestedPermissions.orEmpty()) try { ua.executeShellCommand("pm grant $pkg $p").close() } catch (e: Exception) {}
        for (c in SPECIAL) try { ua.executeShellCommand(c.format(pkg)).close() } catch (e: Exception) {}
    }


    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val GATE_MS = 5_000L
        const val GATE = "Grant Required Permissions"
        val SPECIAL = arrayOf("appops set %s SCHEDULE_EXACT_ALARM allow", "appops set %s USE_FULL_SCREEN_INTENT allow")
    }
}
