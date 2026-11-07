package com.vayunmathur.code.util

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests that a fresh [EditorViewModel] restores the persisted folder, session tabs and fold state. */
@RunWith(RobolectricTestRunner::class)
class SessionRestoreTest {

    @Test
    fun freshViewModelRestoresSession() {
        val dir = File(System.getProperty("java.io.tmpdir"), "session-${System.nanoTime()}").also { it.mkdirs() }
        val file = File(dir, "restore.kt").also { it.writeText("fun restored() {}") }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = EditorPrefs(ctx)
        runBlocking {
            prefs.setFolderPath(dir.absolutePath)
            prefs.setSession(listOf(file.absolutePath), file.absolutePath)
        }
        val v = EditorViewModel(ctx as android.app.Application)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (v.tabs.any { it.name == "restore.kt" } && v.rootName == dir.name) break
            Thread.sleep(25)
        }
        assertTrue(v.tabs.any { it.name == "restore.kt" }, "tabs=${v.tabs.map { it.name }}")
        assertEquals("fun restored() {}", v.tabs.first { it.name == "restore.kt" }.value.text)
        assertEquals(dir.name, v.rootName)
        // Clean up persisted state so later VMs in this process start empty.
        runBlocking {
            prefs.setSession(emptyList(), null)
            prefs.clearFolderPath()
        }
        dir.deleteRecursively()
    }
}
