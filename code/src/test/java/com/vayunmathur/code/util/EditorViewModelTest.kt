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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric tests for [EditorViewModel] pure-state paths: tabs, prefs setters, snippets, terminal. */
@RunWith(RobolectricTestRunner::class)
class EditorViewModelTest {

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    private fun openTab(name: String = "a.kt", text: String = "hello"): OpenTab =
        OpenTab(null, initialName = name, initialText = text, language = Language.KOTLIN)

    @Test
    fun freshViewModelHasNoTabs() {
        val v = vm()
        assertTrue(v.tabs.isEmpty())
        assertEquals(-1, v.currentIndex)
        assertNull(v.currentTab)
        assertNull(v.activeTab)
        assertNull(v.uiState.currentTab)
    }

    @Test
    fun currentTabAndActiveTabFollowSelection() {
        val v = vm()
        v.tabs.add(openTab("a.kt"))
        v.tabs.add(openTab("b.kt"))
        v.currentIndex = 1
        assertEquals("b.kt", v.currentTab?.name)
        assertEquals("b.kt", v.activeTab?.name)
        // Focused secondary with no secondary tab: the VM resolves null (focusPane
        // guards this state by requiring secondaryIndex != null; the UI-state
        // activeTab instead falls back to the current tab).
        v.focusedSecondary = true
        assertNull(v.activeTab?.name)
        v.secondaryIndex = 0
        assertEquals("a.kt", v.activeTab?.name)
    }

    @Test
    fun uiStateProjectsTabsAndPrefs() {
        val v = vm()
        v.tabs.add(openTab("a.kt", "x"))
        v.currentIndex = 0
        v.softWrap = true
        val ui = v.uiState
        assertEquals(1, ui.tabs.size)
        assertEquals("a.kt", ui.tabs[0].name)
        assertEquals("x", ui.tabs[0].value.text)
        assertEquals(0, ui.currentIndex)
        assertEquals(-1, ui.secondaryIndex)
        assertFalse(ui.focusedSecondary)
        assertTrue(ui.softWrap)
        assertEquals(v.fontSize, ui.fontSize)
        assertEquals(v.tabWidth, ui.tabWidth)
        assertTrue(ui.autoIndent)
        assertTrue(ui.autoCloseBrackets)
    }

    @Test
    fun snippetAddUpdateDelete() {
        val v = vm()
        v.addSnippet(UserSnippet("a", "x"))
        v.addSnippet(UserSnippet("b", "y"))
        assertEquals(listOf(UserSnippet("a", "x"), UserSnippet("b", "y")), v.userSnippets.toList())
        v.updateSnippet(0, UserSnippet("a2", "x2"))
        assertEquals(UserSnippet("a2", "x2"), v.userSnippets[0])
        // Out-of-range updates are ignored.
        v.updateSnippet(9, UserSnippet("z", "z"))
        assertEquals(2, v.userSnippets.size)
        v.deleteSnippet(0)
        assertEquals(listOf(UserSnippet("b", "y")), v.userSnippets.toList())
        v.deleteSnippet(9)
        assertEquals(1, v.userSnippets.size)
    }

    @Test
    fun settingsSettersUpdateState() {
        val v = vm()
        v.setAutoSave(true)
        assertTrue(v.autoSave)
        v.setTrimTrailingOnSave(true)
        assertTrue(v.trimTrailingOnSave)
        v.setFinalNewlineOnSave(true)
        assertTrue(v.finalNewlineOnSave)
        v.setEditorTheme("monokai")
        assertEquals("monokai", v.editorTheme)
        v.setExperimentalEditor(true)
        assertTrue(v.experimentalEditor)
        v.setShowWhitespace(true)
        assertTrue(v.showWhitespace)
        v.setShowIndentGuides(true)
        assertTrue(v.showIndentGuides)
        v.setShowMinimap(true)
        assertTrue(v.showMinimap)
    }

    @Test
    fun closeFolderClearsTree() {
        val v = vm()
        v.rootDir = java.io.File("/tmp")
        v.rootName = "tmp"
        assertEquals("tmp", v.uiState.rootName)
        assertTrue(v.uiState.folderOpen)
        v.closeFolder()
        assertNull(v.rootDir)
        assertNull(v.rootName)
        assertTrue(v.nodes.isEmpty())
        assertFalse(v.uiState.folderOpen)
    }

    @Test
    fun buildRegexRespectsCaseSensitivity() {
        val v = vm()
        assertTrue(v.buildRegex("foo", true).matches("foo"))
        assertFalse(v.buildRegex("foo", true).matches("FOO"))
        assertTrue(v.buildRegex("foo", false).matches("FOO"))
    }

    @Test
    fun clearTerminalEmptiesLines() {
        val v = vm()
        v.appendTerminalLine("a")
        v.appendTerminalLine("b")
        assertEquals(2, v.terminalLines.size)
        v.clearTerminal()
        assertTrue(v.terminalLines.isEmpty())
    }

    @Test
    fun appendTerminalLineCapsScrollback() {
        val v = vm()
        repeat(TERMINAL_SCROLLBACK + 50) { v.appendTerminalLine("l$it") }
        assertEquals(TERMINAL_SCROLLBACK, v.terminalLines.size)
        assertEquals("l50", v.terminalLines.first())
    }

    @Test
    fun terminalInterruptWithoutTerminalAppendsMarker() {
        val v = vm()
        assertFalse(v.terminalRunning)
        // No folder open: startTerminal is a no-op, interrupt only appends ^C.
        v.terminalInterrupt()
        assertTrue(v.terminalLines.contains("^C"))
        assertFalse(v.terminalRunning)
    }

    @Test
    fun clearGitDiffAndMessage() {
        val v = vm()
        v.gitDiff = "diff"
        v.gitDiffRows = emptyList()
        v.gitMessage = "msg"
        v.clearGitDiff()
        v.clearDiffRows()
        v.clearGitMessage()
        assertNull(v.gitDiff)
        assertNull(v.gitDiffRows)
        assertNull(v.gitMessage)
    }

    @Test
    fun setGitCredentialsUpdateState() {
        val v = vm()
        v.setGitUsername("u")
        v.setGitToken("t")
        v.setGitAuthorName("n")
        v.setGitAuthorEmail("e")
        assertEquals("u", v.gitUsername)
        assertEquals("t", v.gitToken)
        assertEquals("n", v.gitAuthorName)
        assertEquals("e", v.gitAuthorEmail)
    }

    @Test
    fun applyLineEditWithNoTabIsNoOp() {
        val v = vm()
        v.applyLineEdit(::duplicateLine) // no crash, no tabs
        assertTrue(v.tabs.isEmpty())
    }

    @Test
    fun commitEditWithNoTabIsNoOp() {
        val v = vm()
        v.commitEdit(openTab(), TextFieldValue("x", TextRange(1))) // detached tab: still fine
    }
}
