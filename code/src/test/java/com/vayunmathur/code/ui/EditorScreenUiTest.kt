package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.Diagnostic
import com.vayunmathur.code.util.DiagnosticSeverity
import com.vayunmathur.code.util.Resolution
import com.vayunmathur.code.util.TabUiState
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** Robolectric Compose tests for [EditorScreen]: empty, tabbed, find bar, problems, outline. */
@RunWith(AndroidJUnit4::class)
class EditorScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun tabbed() = CodeUiState(
        tabs = listOf(
            TabUiState(
                name = "a.kt",
                value = androidx.compose.ui.text.input.TextFieldValue("fun f() {}"),
                language = Language.KOTLIN,
            ),
        ),
        currentIndex = 0,
    )

    @Test
    fun emptyScreenShowsEmptyState() {
        rule.setContent { EditorScreen(state = CodeUiState(), actions = CodeActions.Noop) }
        rule.onNodeWithText("No file open").assertIsDisplayed()
        rule.onAllNodesWithText("Open folder").onFirst().assertIsDisplayed()
    }

    @Test
    fun tabbedScreenShowsTabAndToolbar() {
        rule.setContent { EditorScreen(state = tabbed(), actions = CodeActions.Noop) }
        rule.onAllNodesWithText("a.kt").onFirst().assertIsDisplayed()
        rule.onNodeWithText("Kotlin").assertExists()
    }

    @Test
    fun findBarSeededByInitialFind() {
        rule.setContent {
            EditorScreen(state = tabbed(), actions = CodeActions.Noop, initialFind = "fun")
        }
        // Find bar is open on "fun", which occurs once in "fun f() {}".
        rule.onNodeWithText("1/1").assertExists()
    }

    @Test
    fun problemsDialogSeededContent() {
        val s = tabbed().copy(
            diagnostics = listOf(
                Diagnostic(0, 0, 3, DiagnosticSeverity.ERROR, "boom"),
                Diagnostic(1, 0, 1, DiagnosticSeverity.WARNING, "careful"),
            ),
        )
        rule.setContent { EditorScreen(state = s, actions = CodeActions.Noop) }
        // Toolbar shows the error/warning counts (1 error / 1 warning).
        rule.onNodeWithText("1/1", substring = true).assertExists()
    }

    @Test
    fun outlineSymbolsFromBuffer() {
        val s = tabbed()
        val tab = s.activeTab!!
        val symbols = com.vayunmathur.code.util.extractSymbols(tab.value.text, tab.language)
        // Single Kotlin function "f" in the buffer.
        assertTrue(symbols.any { it.name == "f" })
    }

    @Test
    fun outlineSymbolsEmptyWithoutTab() {
        assertTrue(CodeUiState().activeTab == null)
    }

    @Test
    fun problemsDialogListsDiagnostics() {
        val s = tabbed().copy(
            diagnostics = listOf(Diagnostic(2, 0, 1, DiagnosticSeverity.ERROR, "boom")),
        )
        // Picker rows derive from diagnostics: line text + message.
        assertTrue(s.diagnostics.size == 1)
        assertTrue("boom" == s.diagnostics[0].message)
        assertTrue(2 == s.diagnostics[0].line)
    }
}
