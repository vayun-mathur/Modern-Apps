package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.code.syntax.Language
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Robolectric tests for the synchronous action-delegate paths (tabs, edits, folds, completion accept). */
@RunWith(RobolectricTestRunner::class)
class EditorActionsTest {

    private fun vm(): EditorViewModel {
        val v = EditorViewModel(ApplicationProvider.getApplicationContext())
        // Keep diagnostics from firing (debounced coroutine).
        v.diagnosticsJob?.cancel()
        return v
    }

    private fun openTab(name: String = "a.kt", text: String = "line1\nline2"): OpenTab =
        OpenTab(null, initialName = name, initialText = text, language = Language.KOTLIN)

    private fun vmWithTabs(vararg names: String): EditorViewModel {
        val v = vm()
        names.forEach { v.tabs.add(openTab(it)) }
        v.currentIndex = 0
        return v
    }

    // ---- Tab actions ----

    @Test
    fun selectTabMovesCurrentAndClearsSecondaryCollision() {
        val v = vmWithTabs("a.kt", "b.kt")
        v.secondaryIndex = 1
        v.actions.selectTab(1)
        assertEquals(1, v.currentIndex)
        assertEquals(null, v.secondaryIndex)
        assertFalse(v.focusedSecondary)
    }

    @Test
    fun selectTabOutOfRangeIsNoOp() {
        val v = vmWithTabs("a.kt")
        v.actions.selectTab(9)
        assertEquals(0, v.currentIndex)
        v.actions.selectTab(-1)
        assertEquals(0, v.currentIndex)
    }

    @Test
    fun closeTabAdjustsIndices() {
        val v = vmWithTabs("a.kt", "b.kt", "c.kt")
        v.currentIndex = 1
        v.actions.closeTab(0)
        assertEquals(0, v.currentIndex)
        assertEquals(listOf("b.kt", "c.kt"), v.tabs.map { it.name })
        // Closing the current tab moves to the same index (now the next tab).
        v.actions.closeTab(0)
        assertEquals(0, v.currentIndex)
        assertEquals("c.kt", v.currentTab?.name)
        v.actions.closeTab(0)
        assertEquals(-1, v.currentIndex)
        assertTrue(v.tabs.isEmpty())
    }

    @Test
    fun closeTabOutOfRangeIsNoOp() {
        val v = vmWithTabs("a.kt")
        v.actions.closeTab(5)
        assertEquals(1, v.tabs.size)
    }

    @Test
    fun closeTabShiftsSecondaryDown() {
        val v = vmWithTabs("a.kt", "b.kt", "c.kt")
        v.currentIndex = 0
        v.secondaryIndex = 2
        v.actions.closeTab(1)
        assertEquals(1, v.secondaryIndex)
        // Closing the secondary clears it.
        v.actions.closeTab(1)
        assertEquals(null, v.secondaryIndex)
        assertFalse(v.focusedSecondary)
    }

    @Test
    fun undoRedoDelegateToActiveTab() {
        val v = vmWithTabs("a.kt")
        val tab = v.currentTab!!
        tab.pushUndo(tab.value)
        tab.value = TextFieldValue("changed", TextRange(7))
        v.actions.undo()
        assertEquals("line1\nline2", tab.value.text)
        v.actions.redo()
        assertEquals("changed", tab.value.text)
    }

    @Test
    fun toggleSoftWrapFlips() {
        val v = vm()
        val before = v.softWrap
        v.actions.toggleSoftWrap()
        assertEquals(!before, v.softWrap)
    }

    @Test
    fun insertTextEditsActiveTab() {
        val v = vmWithTabs("a.kt")
        val tab = v.currentTab!!
        tab.value = TextFieldValue("helloworld", TextRange(5))
        v.actions.insertText(" ")
        assertEquals("hello world", tab.value.text)
    }

    @Test
    fun onEditorChangeRoutesToCurrentTab() {
        val v = vmWithTabs("a.kt")
        v.actions.onEditorChange(TextFieldValue("typed", TextRange(5)))
        assertEquals("typed", v.currentTab?.value?.text)
    }

    @Test
    fun onSecondaryEditorChangeRoutesToSecondary() {
        val v = vmWithTabs("a.kt", "b.kt")
        v.secondaryIndex = 1
        v.actions.onSecondaryEditorChange(TextFieldValue("sec", TextRange(3)))
        assertEquals("sec", v.tabs[1].value.text)
        assertEquals("line1\nline2", v.tabs[0].value.text)
    }

    @Test
    fun toggleSplitOpensAndCloses() {
        val v = vmWithTabs("a.kt", "b.kt")
        assertEquals(null, v.secondaryIndex)
        v.actions.toggleSplit()
        assertEquals(1, v.secondaryIndex)
        v.actions.toggleSplit()
        assertEquals(null, v.secondaryIndex)
        assertFalse(v.focusedSecondary)
    }

    @Test
    fun toggleSplitNeedsTwoTabs() {
        val v = vmWithTabs("a.kt")
        v.actions.toggleSplit()
        assertEquals(null, v.secondaryIndex)
        val empty = vm()
        empty.actions.toggleSplit()
        assertEquals(null, empty.secondaryIndex)
    }

    @Test
    fun focusPaneOnlyWhenSecondaryExists() {
        val v = vmWithTabs("a.kt", "b.kt")
        v.actions.focusPane(true)
        assertFalse(v.focusedSecondary)
        v.secondaryIndex = 1
        v.actions.focusPane(true)
        assertTrue(v.focusedSecondary)
        v.actions.focusPane(false)
        assertFalse(v.focusedSecondary)
    }

    // ---- Edit actions ----

    @Test
    fun toggleCommentUsesLanguagePrefix() {
        val v = vmWithTabs("a.kt")
        v.currentTab?.value = TextFieldValue("foo", TextRange(0))
        v.actions.toggleComment()
        assertEquals("// foo", v.currentTab?.value?.text)
        v.actions.toggleComment()
        assertEquals("foo", v.currentTab?.value?.text)
    }

    @Test
    fun duplicateMoveDeleteLine() {
        val v = vmWithTabs("a.kt")
        v.currentTab?.value = TextFieldValue("foo\nbar", TextRange(1))
        v.actions.duplicateLine()
        assertEquals("foo\nfoo\nbar", v.currentTab?.value?.text)
        v.actions.deleteLine()
        // After duplicating, the caret sits on the copy (line 1, "foo"); deleting it
        // removes the copy, leaving the original buffer.
        assertEquals("foo\nbar", v.currentTab?.value?.text)
        // Caret is on line 1 ("bar"): move up swaps, move down swaps back.
        v.actions.moveLineUp()
        assertEquals("bar\nfoo", v.currentTab?.value?.text)
        v.actions.moveLineDown()
        assertEquals("foo\nbar", v.currentTab?.value?.text)
        // First line: move up is a no-op.
        v.currentTab?.value = TextFieldValue("foo\nbar", TextRange(1))
        v.actions.moveLineUp()
        assertEquals("foo\nbar", v.currentTab?.value?.text)
    }

    @Test
    fun formatDocumentJson() {
        val v = vmWithTabs("a.json")
        v.currentTab?.language = Language.JSON
        v.currentTab?.value = TextFieldValue("{\"a\":1}", TextRange(7))
        v.actions.formatDocument()
        assertEquals("{\n  \"a\": 1\n}", v.currentTab?.value?.text)
    }

    @Test
    fun formatDocumentNoOpForUnsupportedLanguage() {
        val v = vmWithTabs("a.kt")
        val before = v.currentTab?.value
        v.actions.formatDocument()
        assertEquals(before, v.currentTab?.value)
    }

    @Test
    fun resolveConflictsAppliesChoice() {
        val v = vmWithTabs("a.kt")
        v.currentTab?.value = TextFieldValue(
            "a\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> b\nc",
            TextRange(0),
        )
        v.actions.resolveConflicts(listOf(Resolution.OURS))
        assertEquals("a\nours\nc", v.currentTab?.value?.text)
    }

    @Test
    fun setSelectionAndGoToLine() {
        val v = vmWithTabs("a.kt")
        v.actions.setSelection(TextRange(2, 4))
        assertEquals(TextRange(2, 4), v.currentTab?.value?.selection)
        v.actions.goToLine(2)
        assertEquals(6, v.currentTab?.value?.selection?.start)
    }

    @Test
    fun foldsToggleFoldAllUnfoldAll() {
        val v = vmWithTabs("a.kt")
        v.currentTab?.value = TextFieldValue("a\n  b\n  c\nd", TextRange(0))
        v.actions.toggleFold(0)
        assertTrue(0 in v.currentTab?.foldedHeaders!!)
        v.actions.toggleFold(0)
        assertFalse(0 in v.currentTab?.foldedHeaders!!)
        v.actions.foldAllInTab()
        assertTrue(v.currentTab?.foldedHeaders?.isNotEmpty() == true)
        v.actions.unfoldAll()
        assertTrue(v.currentTab?.foldedHeaders?.isEmpty() == true)
    }

    @Test
    fun replaceRangeAndReplaceAll() {
        val v = vmWithTabs("a.kt")
        v.currentTab?.value = TextFieldValue("hello world", TextRange(0))
        v.actions.replaceRange(0..4, "bye")
        assertEquals("bye world", v.currentTab?.value?.text)
        v.currentTab?.value = TextFieldValue("a a a", TextRange(0))
        v.actions.replaceAll(listOf(0..0, 2..2, 4..4), "b")
        assertEquals("b b b", v.currentTab?.value?.text)
        // Out-of-range and empty are no-ops.
        v.actions.replaceRange(50..60, "x")
        v.actions.replaceAll(emptyList(), "x")
        assertEquals("b b b", v.currentTab?.value?.text)
    }

    @Test
    fun replaceRegex() {
        val v = vmWithTabs("a.kt")
        v.currentTab?.value = TextFieldValue("foo 123 bar", TextRange(0))
        v.actions.replaceAllRegex("\\d+", "#", caseSensitive = true)
        assertEquals("foo # bar", v.currentTab?.value?.text)
        v.currentTab?.value = TextFieldValue("a1b2", TextRange(0))
        v.actions.replaceMatchRegex(0..1, "(a)(1)", "$2$1", caseSensitive = true)
        assertEquals("1ab2", v.currentTab?.value?.text)
        // Invalid pattern is a no-op.
        v.actions.replaceAllRegex("(", "x", caseSensitive = true)
        assertEquals("1ab2", v.currentTab?.value?.text)
    }

    // ---- Search actions ----

    @Test
    fun acceptCompletionReplacesPrefix() {
        val v = vmWithTabs("a.kt")
        v.currentTab?.value = TextFieldValue("val coun", TextRange(8))
        v.actions.acceptCompletion(Completion("counter", "counter", 7, CompletionKind.WORD))
        assertEquals("val counter", v.currentTab?.value?.text)
        assertEquals(11, v.currentTab?.value?.selection?.start)
    }

    @Test
    fun dismissCompletionsClears() {
        val v = vmWithTabs("a.kt")
        v.completions.add(Completion("x", "x", 1, CompletionKind.WORD))
        v.showCompletions = true
        v.actions.dismissCompletions()
        assertTrue(v.completions.isEmpty())
        assertFalse(v.showCompletions)
    }

    // ---- Settings actions ----

    @Test
    fun settingsActionsUpdateState() {
        val v = vm()
        v.actions.setFontSize(22)
        assertEquals(22, v.fontSize)
        v.actions.setTabWidth(2)
        assertEquals(2, v.tabWidth)
        v.actions.setThemeMode("dark")
        assertEquals("dark", v.themeMode)
        v.actions.setAutoIndent(false)
        assertFalse(v.autoIndent)
        v.actions.setAutoCloseBrackets(false)
        assertFalse(v.autoCloseBrackets)
    }

    // ---- Save actions ----

    @Test
    fun saveAllSkipsCleanTabs() {
        val v = vmWithTabs("a.kt")
        // No file backing: saveAll is a no-op (no crash).
        v.actions.saveAll()
        v.actions.save()
    }
}
