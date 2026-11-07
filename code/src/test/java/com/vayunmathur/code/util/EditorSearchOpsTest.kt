package com.vayunmathur.code.util

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Tests for the pure project-search helpers: path math, gitignore loading, search constants. */
@RunWith(RobolectricTestRunner::class)
class EditorSearchOpsTest {

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun relativeToStripsRootPrefix() {
        val root = File("/proj")
        assertEquals("a/b.kt", relativeTo(root, File("/proj/a/b.kt")))
        assertEquals("other.kt", relativeTo(root, File("/elsewhere/other.kt")))
    }

    @Test
    fun loadGitIgnoreEmptyWhenMissing() {
        val dir = File(System.getProperty("java.io.tmpdir"), "gi-${System.nanoTime()}").also { it.mkdirs() }
        val ignore = loadGitIgnore(dir)
        assertFalse(ignore.isIgnored("a.kt", false))
        dir.deleteRecursively()
    }

    @Test
    fun loadGitIgnoreParsesFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "gi2-${System.nanoTime()}").also { it.mkdirs() }
        File(dir, ".gitignore").writeText("*.log\n")
        val ignore = loadGitIgnore(dir)
        assertTrue(ignore.isIgnored("a.log", false))
        assertFalse(ignore.isIgnored("a.kt", false))
        dir.deleteRecursively()
    }

    @Test
    fun toProjectEntryUsesRelativePathUnderRoot() {
        val v = vm()
        v.rootDir = File("/proj")
        val entry = v.toProjectEntry(File("/proj/src/a.kt"))
        assertEquals("/proj/src/a.kt", entry.path)
        assertEquals("a.kt", entry.name)
        assertEquals("src/a.kt", entry.relativePath)
    }

    @Test
    fun toProjectEntryFallsBackToAbsoluteWhenOutsideRoot() {
        val v = vm()
        v.rootDir = File("/proj")
        val entry = v.toProjectEntry(File("/other/a.kt"))
        assertEquals("/other/a.kt", entry.relativePath)
        // No root at all: relative path is the absolute path.
        val v2 = vm()
        assertEquals("/other/a.kt", v2.toProjectEntry(File("/other/a.kt")).relativePath)
    }

    @Test
    fun addRecentFileCapsAndDedups() {
        val v = vm()
        v.addRecentFile("/a.kt")
        v.addRecentFile("/b.kt")
        v.addRecentFile("/a.kt")
        // Most recent first, no duplicates.
        assertEquals(listOf("/a.kt", "/b.kt"), v.uiState.recentFiles.map { it.path }.take(2))
        repeat(MAX_RECENT_FILES + 5) { v.addRecentFile("/f$it.kt") }
        assertTrue(v.uiState.recentFiles.size <= MAX_RECENT_FILES)
    }

    @Test
    fun searchConstantsAreSane() {
        assertEquals(500, MAX_SEARCH_RESULTS)
        assertEquals(50, MAX_MATCHES_PER_FILE)
        assertEquals(500_000, MAX_SEARCH_FILE_SIZE)
        assertEquals(5000, MAX_PROJECT_FILES)
        assertEquals(15, MAX_RECENT_FILES)
        assertTrue(".git" in SKIP_DIRS)
        assertTrue("build" in SKIP_DIRS)
    }

    @Test
    fun completionDebounceConstants() {
        assertEquals(150L, COMPLETIONS_DELAY_MS)
        assertEquals(1, MIN_COMPLETION_PREFIX)
        assertEquals(50, MAX_COMPLETIONS)
        assertEquals(1_000_000, MAX_COMPLETION_BUFFER_CHARS)
    }
}
