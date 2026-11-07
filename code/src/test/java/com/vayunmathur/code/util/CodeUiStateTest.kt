package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vayunmathur.code.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the UI contract state: derived getters and picker-item builders. */
class CodeUiStateTest {

    private fun tab(name: String) = TabUiState(
        name = name,
        value = TextFieldValue("content of $name", TextRange(3)),
        language = Language.KOTLIN,
    )

    @Test
    fun currentTabFollowsIndex() {
        val state = CodeUiState(tabs = listOf(tab("a.kt"), tab("b.kt")), currentIndex = 1)
        assertEquals("b.kt", state.currentTab?.name)
        assertEquals("a.kt", state.copy(currentIndex = 0).currentTab?.name)
        assertNull(state.copy(currentIndex = 5).currentTab)
        assertNull(CodeUiState().currentTab)
    }

    @Test
    fun secondaryTabFollowsSecondaryIndex() {
        val state = CodeUiState(tabs = listOf(tab("a.kt"), tab("b.kt")), secondaryIndex = 0)
        assertEquals("a.kt", state.secondaryTab?.name)
        assertNull(state.copy(secondaryIndex = -1).secondaryTab)
    }

    @Test
    fun activeTabRespectsFocusedPane() {
        val state = CodeUiState(
            tabs = listOf(tab("a.kt"), tab("b.kt")),
            currentIndex = 0,
            secondaryIndex = 1,
            focusedSecondary = true,
        )
        assertEquals("b.kt", state.activeTab?.name)
        assertEquals("a.kt", state.copy(focusedSecondary = false).activeTab?.name)
        // Focused secondary without a valid secondary tab falls back to the current tab.
        assertEquals("a.kt", state.copy(secondaryIndex = -1).activeTab?.name)
        assertNull(CodeUiState().activeTab)
    }

    @Test
    fun errorAndWarningCountsSplitBySeverity() {
        fun diag(sev: DiagnosticSeverity) = Diagnostic(0, 0, 0, sev, "m")
        val state = CodeUiState(
            diagnostics = listOf(
                diag(DiagnosticSeverity.ERROR),
                diag(DiagnosticSeverity.ERROR),
                diag(DiagnosticSeverity.WARNING),
                diag(DiagnosticSeverity.INFO),
            ),
        )
        assertEquals(2, state.errorCount)
        assertEquals(1, state.warningCount)
        assertEquals(0, CodeUiState().errorCount)
        assertEquals(0, CodeUiState().warningCount)
    }

    @Test
    fun tabUiStateDefaults() {
        val t = TabUiState(name = "x")
        assertEquals(TextFieldValue(), t.value)
        assertEquals(Language.PLAINTEXT, t.language)
        assertFalse(t.isDirty)
        assertFalse(t.canUndo)
        assertFalse(t.canRedo)
        assertFalse(t.changedOnDisk)
        assertEquals("UTF-8", t.charsetName)
        assertEquals("LF", t.lineEndingName)
        assertTrue(t.foldedHeaders.isEmpty())
        assertEquals(t, t.copy())
    }

    @Test
    fun treeRowAndSearchResultHoldTheirFields() {
        val row = TreeRowUiState(name = "src", depth = 1, isDirectory = true, expanded = true)
        assertEquals("src", row.name)
        assertEquals(1, row.depth)
        assertTrue(row.isDirectory)
        assertTrue(row.expanded)
        assertEquals(TreeRowUiState(name = "f"), TreeRowUiState(name = "f").copy())

        val hit = SearchResult(path = "/p/a.kt", name = "a.kt", line = 7, preview = "fun a()")
        assertEquals(7, hit.line)
        assertEquals("fun a()", hit.preview)
        assertEquals(hit, hit.copy())
    }

    @Test
    fun projectFileEntryHoldsPaths() {
        val e = ProjectFileEntry(path = "/root/a.kt", name = "a.kt", relativePath = "a.kt")
        assertEquals("/root/a.kt", e.path)
        assertEquals("a.kt", e.relativePath)
    }
}
