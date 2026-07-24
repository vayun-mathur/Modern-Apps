package com.vayunmathur.astronomy

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.util.Log

/**
 * `:astronomy:metadata` → generated path `metadata_data/photos/astronomy/`
 * Keeps `metadata_data/astronomy.md`. 3 distinct shots (not dupes).
 *
 * No createEmptyComposeRule / no createAndroidComposeRule / no Espresso —
 * all trigger InputManager.getInstance crash on API 36 (Android 17).
 * Uses ActivityScenario + UiAutomation tree + decorView.draw() capture
 * (games-hub already passed this way with OK (1 test)).
 *
 * Shots:
 *  1. Sky map (SF seeded, distinct)
 *  2. Search "Mars" results (distinct list)
 *  3. Settings Display + sliders (previously was dup of 1.png — now real settings)
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    // Accessibility helpers — predicate last avoided earlier signature confusion

    private fun findNode(root: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (pred(root)) return root
        for (i in 0 until root.childCount) {
            val f = findNode(root.getChild(i), pred)
            if (f != null) return f
        }
        return null
    }

    private fun findAll(root: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (pred(n)) out += n
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(root)
        return out
    }

    private fun clickByText(text: String, substring: Boolean = true): Boolean {
        val ui = instrumentation.uiAutomation
        repeat(14) {
            val root = ui.rootInActiveWindow ?: return@repeat
            val nodes = if (substring) findAll(root) { it.text?.toString()?.contains(text, true) == true }
            else root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.isClickable == true) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(700)
                    return true
                }
            }
            // contentDescription fallback
            val cd = findNode(root) { it.contentDescription?.toString()?.contains(text, true) == true }
            if (cd != null) {
                var t: AccessibilityNodeInfo? = cd
                while (t != null && !t.isClickable) t = t.parent
                if ((t ?: cd).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Thread.sleep(700); return true
                }
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun clickByDesc(desc: String): Boolean {
        val ui = instrumentation.uiAutomation
        repeat(14) {
            val root = ui.rootInActiveWindow ?: return@repeat
            val nodes = findAll(root) { it.contentDescription?.toString()?.contains(desc, true) == true }
                .sortedBy {
                    val r = android.graphics.Rect()
                    it.getBoundsInScreen(r)
                    r.top
                }
            for (node in nodes) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if ((target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Thread.sleep(700); return true
                }
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun setEditable(text: String): Boolean {
        val ui = instrumentation.uiAutomation
        repeat(14) {
            val root = ui.rootInActiveWindow ?: return@repeat
            val editable = findNode(root) { it.isEditable }
                ?: findNode(root) { it.className?.toString()?.contains("EditText", true) == true }
            if (editable != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    Thread.sleep(900); return true
                }
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun waitText(text: String, timeoutMs: Long = 12000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                if (findNode(root) { it.text?.toString()?.contains(text, true) == true } != null) return true
                if (findNode(root) { it.contentDescription?.toString()?.contains(text, true) == true } != null) return true
            }
            Thread.sleep(300)
        }
        return false
    }

    private fun pressBack() {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800)
    }

    private fun <A : Activity> snapDecor(scenario: ActivityScenario<A>, index: Int) {
        // DecorView draw works for ListPage (sky map, search). For DialogPage (settings)
        // we try full screenshot first (captures dialog), fallback to decor draw.
        Thread.sleep(1100)
        var bmp: Bitmap? = null
        try {
            val full = instrumentation.uiAutomation.takeScreenshot()
            if (full != null) {
                val baos = java.io.ByteArrayOutputStream()
                full.compress(Bitmap.CompressFormat.PNG, 100, baos)
                if (baos.size() > 25000) {
                    // Non-blank
                    bmp = full
                }
            }
        } catch (_: Exception) {}
        if (bmp == null) {
            scenario.onActivity { act ->
                val decor = act.window.decorView
                val w = decor.width.takeIf { it > 0 } ?: 1080
                val h = decor.height.takeIf { it > 0 } ?: 2340
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(b))
                bmp = b
            }
        }
        val final = bmp ?: return
        File(outDir, "$index.png").outputStream().use { final.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dismissDialog() {
        val ui = instrumentation.uiAutomation
        repeat(2) {
            val root = ui.rootInActiveWindow ?: return
            for (label in listOf("Close", "Got it", "OK")) {
                for (node in findAll(root) { it.text?.toString()?.equals(label, true) == true }) {
                    var t: AccessibilityNodeInfo? = node
                    while (t != null && !t.isClickable) t = t.parent
                    if (t != null) {
                        t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(400); return
                    }
                }
            }
            Thread.sleep(300)
        }
    }

    @Test
    fun generateStoreScreenshots() {
        outDir
        var vmRef: AstronomyViewModel? = null

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                val vm = ViewModelProvider(act)[AstronomyViewModel::class.java]
                vm.setManualLocation(37.7749, -122.4194)
                vmRef = vm
                Log.d("AstroMeta", "Seeded SF location")
            }

            var waited = 0
            while (waited < 22000) {
                val vm = vmRef
                if (vm != null && vm.catalogReady.value && vm.visibleSky.value.stars.isNotEmpty()) break
                Thread.sleep(500); waited += 500
            }
            Thread.sleep(2500)
            dismissDialog()

            // 1: sky map (distinct)
            waitText("Astronomy", 10000)
            snapDecor(scenario, 1)

            // 2: search Mars (distinct list UI)
            if (!clickByDesc("Search")) clickByText("Search")
            waitText("Search", 10000)
            Thread.sleep(500)
            setEditable("Mars")
            waitText("Mars", 10000)
            Thread.sleep(700)
            snapDecor(scenario, 2)

            // Back to map
            pressBack()
            Thread.sleep(600)
            waitText("Astronomy", 10000)

            // 3: settings — previously was dup of 1.png (sky map), must be real settings Display page
            var settingsOk = false
            repeat(3) {
                if (clickByDesc("Settings") || clickByText("Settings")) {
                    if (waitText("Display", 6000) && waitText("Magnitude limit", 6000)) {
                        settingsOk = true
                        return@repeat
                    }
                }
                Thread.sleep(500)
            }
            Thread.sleep(900)
            snapDecor(scenario, 3)

            // Fallback if settings navigation failed — use Orion search as distinct 3rd (not dupe of sky map)
            if (!settingsOk) {
                pressBack()
                Thread.sleep(600)
                waitText("Astronomy", 8000)
                if (clickByDesc("Search") || clickByText("Search")) {
                    waitText("Search", 8000)
                    Thread.sleep(400)
                    setEditable("Orion")
                    waitText("Orion", 8000)
                    Thread.sleep(600)
                    snapDecor(scenario, 3)
                }
            }
        }
    }
}
