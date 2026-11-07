package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vayunmathur.code.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for [OpenTab] undo/redo/dirty/key and [TreeNode] defaults. Robolectric (Uri). */
@RunWith(RobolectricTestRunner::class)
class OpenTabTest {

    private fun tab(text: String = "hello") = OpenTab(
        file = null,
        initialName = "a.kt",
        initialText = text,
        language = Language.KOTLIN,
    )

    @Test
    fun freshTabIsCleanWithEmptyUndo() {
        val t = tab()
        assertFalse(t.isDirty)
        assertFalse(t.canUndo)
        assertFalse(t.canRedo)
        assertFalse(t.changedOnDisk)
        assertFalse(t.readOnly)
    }

    @Test
    fun keyFallsBackToExternalUriString() {
        val t = tab()
        t.externalUri = android.net.Uri.parse("content://x/doc/1")
        assertEquals("content://x/doc/1", t.key)
    }

    @Test
    fun keyPrefersFilePath() {
        val f = java.io.File("/tmp/a.kt")
        val t = OpenTab(f, initialName = "a.kt", initialText = "", language = Language.PLAINTEXT)
        assertEquals("/tmp/a.kt", t.key)
    }

    @Test
    fun clearHistoryDropsUndoAndRedo() {
        val t = tab()
        t.pushUndo(t.value)
        t.value = TextFieldValue("changed", TextRange(7))
        assertTrue(t.canUndo)
        t.clearHistory()
        assertFalse(t.canUndo)
        assertFalse(t.canRedo)
        t.undo()
        assertEquals("changed", t.value.text)
    }

    @Test
    fun editingMarksDirty() {
        val t = tab()
        t.value = TextFieldValue("hello!", TextRange(6))
        assertTrue(t.isDirty)
        t.savedText = "hello!"
        assertFalse(t.isDirty)
    }

    @Test
    fun pushUndoEnablesUndoAndClearsRedo() {
        val t = tab()
        val before = t.value
        t.pushUndo(before)
        assertTrue(t.canUndo)
        t.value = TextFieldValue("changed", TextRange(7))
        t.undo()
        assertEquals(before, t.value)
        assertTrue(t.canRedo)
        assertFalse(t.canUndo)
        t.redo()
        assertEquals("changed", t.value.text)
        assertTrue(t.canUndo)
        assertFalse(t.canRedo)
    }

    @Test
    fun undoRedoOnEmptyStacksAreNoOps() {
        val t = tab()
        t.undo()
        t.redo()
        assertEquals("hello", t.value.text)
        assertFalse(t.canUndo)
        assertFalse(t.canRedo)
    }

    @Test
    fun undoStackIsCapped() {
        val t = tab()
        repeat(120) { i -> t.pushUndo(TextFieldValue("v$i")) }
        var undos = 0
        while (t.canUndo) {
            t.undo()
            undos++
        }
        assertEquals(100, undos)
    }

    @Test
    fun redoStackClearedByNewEdit() {
        val t = tab()
        t.pushUndo(t.value)
        t.value = TextFieldValue("v2", TextRange(2))
        t.undo()
        assertTrue(t.canRedo)
        t.pushUndo(t.value)
        assertFalse(t.canRedo)
    }

    @Test
    fun treeNodeDefaults() {
        val entry = FileEntry(java.io.File("/tmp"), "tmp", true)
        val node = TreeNode(entry, 2)
        assertFalse(node.expanded)
        assertFalse(node.loading)
        assertEquals(2, node.depth)
    }

    @Test
    fun gitModelsHoldTheirFields() {
        val clean = GitStatus(branch = "main", staged = emptyList(), unstaged = emptyList(), untracked = emptyList())
        assertTrue(clean.isClean)
        val dirty = clean.copy(untracked = listOf("a.kt"))
        assertFalse(dirty.isClean)
        assertEquals(dirty, dirty.copy())
        val commit = GitCommitInfo(shortId = "abc1234", message = "m", author = "a", timeSeconds = 1L)
        assertEquals("abc1234", commit.shortId)
        assertEquals(commit, commit.copy())
    }
}
