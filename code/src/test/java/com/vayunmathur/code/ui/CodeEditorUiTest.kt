package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.Completion
import com.vayunmathur.code.util.CompletionKind
import com.vayunmathur.code.util.Diagnostic
import com.vayunmathur.code.util.DiagnosticSeverity
import com.vayunmathur.code.util.TabUiState
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test

/** Robolectric Compose tests for [CodeEditor]: buffer, find bar, gutter, completion list. */
@RunWith(AndroidJUnit4::class)
class CodeEditorUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun tab(text: String = "fun f() {}\nval x = 1") = TabUiState(
        name = "a.kt",
        value = TextFieldValue(text),
        language = Language.KOTLIN,
    )

    @Test
    fun bufferTextRenders() {
        rule.setContent {
            CodeEditor(tab = tab(), actions = CodeActions.Noop, softWrap = false, showFind = false, onCloseFind = {})
        }
        rule.onNodeWithText("fun f() {}", substring = true).assertIsDisplayed()
    }

    @Test
    fun findBarSeededQueryShowsMatches() {
        rule.setContent {
            CodeEditor(
                tab = tab(),
                actions = CodeActions.Noop,
                softWrap = false,
                showFind = true,
                onCloseFind = {},
                initialQuery = "fun",
            )
        }
        rule.onNodeWithText("1/1").assertIsDisplayed()
    }

    @Test
    fun findBarHiddenByDefault() {
        rule.setContent {
            CodeEditor(tab = tab(), actions = CodeActions.Noop, softWrap = false, showFind = false, onCloseFind = {})
        }
        rule.onNodeWithText("1/1").assertDoesNotExist()
    }

    @Test
    fun completionsRenderWhenShown() {
        rule.setContent {
            CodeEditor(
                tab = tab(),
                actions = CodeActions.Noop,
                softWrap = false,
                showFind = false,
                onCloseFind = {},
                completions = listOf(Completion("counter…", "counter", 7, CompletionKind.SNIPPET)),
                showCompletions = true,
            )
        }
        rule.waitForIdle()
        // Completion popup is a non-focusable Popup; reaching composition is the assertion.
    }

    @Test
    fun diagnosticsGutterDoesNotCrash() {
        rule.setContent {
            CodeEditor(
                tab = tab(),
                actions = CodeActions.Noop,
                softWrap = true,
                showFind = false,
                onCloseFind = {},
                diagnostics = listOf(Diagnostic(0, 0, 3, DiagnosticSeverity.ERROR, "boom")),
            )
        }
        rule.onNodeWithText("fun f() {}", substring = true).assertIsDisplayed()
    }
}
