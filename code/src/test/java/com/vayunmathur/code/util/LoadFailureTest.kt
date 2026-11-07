package com.vayunmathur.code.util

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.code.syntax.Language
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A failed file read must never fabricate an empty buffer: an empty tab (or an emptied reload)
 * looks clean and can later be saved straight over the real file.
 */
@RunWith(RobolectricTestRunner::class)
class LoadFailureTest {

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
    }

    @Test
    fun openingMissingFileAddsNoTab() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = EditorViewModel(ctx)
        idleMain()
        val before = vm.tabs.size
        vm.openFile(File("/nonexistent-dir-xyz-123/missing.kt"))
        repeat(10) { idleMain(); Thread.sleep(25) }
        assertEquals(before, vm.tabs.size)
    }

    @Test
    fun reloadOfUnreadableFileKeepsBuffer() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = EditorViewModel(ctx)
        idleMain()
        val missing = File("/nonexistent-dir-xyz-123/gone.kt")
        val tab = OpenTab(
            file = missing,
            initialName = "gone.kt",
            initialText = "original",
            language = Language.KOTLIN,
        )
        vm.tabs.add(tab)
        runBlocking { vm.applyReload(tab) }
        assertEquals("original", tab.value.text)
        assertEquals("original", tab.savedText)
        assertTrue(!tab.isDirty)
    }
}
