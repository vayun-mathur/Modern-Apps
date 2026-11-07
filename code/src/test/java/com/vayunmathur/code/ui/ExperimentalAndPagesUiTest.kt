package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.Route
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.Completion
import com.vayunmathur.code.util.CompletionKind
import com.vayunmathur.code.util.Diagnostic
import com.vayunmathur.code.util.DiagnosticSeverity
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.code.util.ProjectFileEntry
import com.vayunmathur.code.util.TabUiState
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric tests for the experimental editor, folder browser, terminal-with-folder, snippet dialogs and command items. */
@RunWith(AndroidJUnit4::class)
class ExperimentalAndPagesUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())
    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Editor))

    private fun tab(text: String, folded: Set<Int> = emptySet()) = TabUiState(
        name = "a.kt",
        value = TextFieldValue(text, TextRange(3)),
        language = Language.KOTLIN,
        foldedHeaders = folded,
    )

    @Test
    fun experimentalPlainWithFindBar() {
        rule.setContent {
            CodeEditorView(
                tab = tab("fun f() {}\nval x = 1\n  indented"),
                actions = CodeActions.Noop, fontSize = 14, editorTheme = "test",
                showFind = true, initialQuery = "fun",
            )
        }
        rule.onNodeWithText("1/1").assertIsDisplayed()
    }

    @Test
    fun experimentalEmptyText() {
        rule.setContent {
            CodeEditorView(tab = tab(""), actions = CodeActions.Noop, fontSize = 14, editorTheme = "test")
        }
        rule.waitForIdle()
    }

    @Test
    fun experimentalWrappedMinimapWhitespaceGuidesDiagnostics() {
        rule.setContent {
            CodeEditorView(
                tab = tab("fun f() {\n    val x = 1\n    val y = 2\n}\nfun g() {}"),
                actions = CodeActions.Noop, fontSize = 14, editorTheme = "test",
                softWrap = true, showWhitespace = true, showIndentGuides = true, showMinimap = true,
                diagnostics = listOf(
                    Diagnostic(1, 4, 5, DiagnosticSeverity.ERROR, "boom"),
                    Diagnostic(2, 4, 5, DiagnosticSeverity.WARNING, "careful"),
                    Diagnostic(0, 0, 3, DiagnosticSeverity.INFO, "note"),
                ),
                completions = listOf(Completion("counter", "counter", 7, CompletionKind.WORD)),
                showCompletions = true,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun experimentalFoldedAndManyLines() {
        val text = (0 until 60).joinToString("\n") { i -> if (i % 10 == 0) "fun f$i() {" else "  x$i" }
        rule.setContent {
            CodeEditorView(
                tab = tab(text, folded = setOf(0, 10)),
                actions = CodeActions.Noop, fontSize = 12, editorTheme = "test",
                showMinimap = true,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun experimentalLongLineAndSelection() {
        val t = TabUiState(
            name = "b.txt", value = TextFieldValue("x".repeat(2500), TextRange(0, 100)),
            language = Language.PLAINTEXT,
        )
        rule.setContent {
            CodeEditorView(tab = t, actions = CodeActions.Noop, fontSize = 14, editorTheme = "test")
        }
        rule.waitForIdle()
    }

    @Test
    fun experimentalSecondaryPane() {
        rule.setContent {
            CodeEditorView(
                tab = tab("hello world hello"),
                actions = CodeActions.Noop, fontSize = 14, editorTheme = "test",
                showFind = true, initialQuery = "hello", secondaryPane = true,
            )
        }
        rule.onNodeWithText("1/2").assertExists()
    }

    @Test
    fun folderBrowserShowsCurrentPathAndOpenButton() {
        rule.setContent { FolderBrowserPage(vm(), backStack()) }
        rule.onNodeWithText("Open this folder").assertIsDisplayed()
        rule.waitForIdle()
    }

    @Test
    fun terminalWithFolderShowsLines() {
        val v = vm()
        v.rootName = "proj"
        v.terminalLines.add("hello terminal")
        v.terminalLines.add("$ ls")
        rule.setContent { TerminalPage(v, backStack()) }
        rule.onNodeWithText("hello terminal").assertIsDisplayed()
        rule.onNodeWithText("$ ls", substring = true).assertIsDisplayed()
    }

    @Test
    fun snippetsAddDialogSavesSnippet() {
        val v = vm()
        rule.setContent { SnippetsPage(v, backStack()) }
        rule.onNodeWithText("Add snippet").performClick()
        rule.onNodeWithText("Trigger").performTextInput("logx")
        rule.onNodeWithText("Template").performTextInput("println(1)")
        rule.onNodeWithText("Save").performClick()
        assertTrue(v.userSnippets.any { it.trigger == "logx" && it.template == "println(1)" }, "snippets=${v.userSnippets}")
    }

    @Test
    fun snippetsEditDialogPrefills() {
        val v = vm()
        v.userSnippets.add(com.vayunmathur.code.util.UserSnippet("log", "println()", "KOTLIN"))
        rule.setContent { SnippetsPage(v, backStack()) }
        rule.onNodeWithText("log").assertIsDisplayed()
        rule.onNodeWithText("Kotlin").assertIsDisplayed()
        rule.onNodeWithText("log").performClick()
        rule.onNodeWithText("Edit snippet").assertIsDisplayed()
    }

    @Test
    fun editorCommandsInvokeCallbacks() {
        var saved = 0
        var found = 0
        val actions = object : CodeActions {
            override fun save() { saved++ }
        }
        var captured: List<PickerItem> = emptyList()
        rule.setContent {
            captured = editorCommands(
                actions = actions,
                onToggleFind = { found++ },
                onGoToLine = {}, onQuickOpen = {}, onOutline = {},
                onResolveConflicts = {}, onProblems = {}, onOpenSearch = {},
                onOpenGit = {}, onOpenTerminal = {}, onOpenPreview = {},
                onOpenSettings = {}, onOpenFolder = {}, onOpenFile = {},
            )
        }
        assertEquals(25, captured.size)
        captured.first { it.primary == "Save" }.onSelect()
        assertEquals(1, saved)
        captured.first { it.primary == "Find" }.onSelect()
        assertEquals(1, found)
    }

    @Test
    fun quickAndRecentItemsMapFiles() {
        val state = CodeUiState(
            projectFiles = listOf(ProjectFileEntry("/p/a.kt", "a.kt", "a.kt")),
            recentFiles = listOf(ProjectFileEntry("/p/b.kt", "b.kt", "b.kt")),
        )
        var opened: String? = null
        val quick = quickOpenItems(state) { opened = it }
        assertEquals(listOf("a.kt"), quick.map { it.primary })
        quick[0].onSelect(); assertEquals("/p/a.kt", opened)
        val recent = recentOpenItems(state) { opened = it }
        recent[0].onSelect(); assertEquals("/p/b.kt", opened)
    }

    @Test
    fun fuzzyPickerNoResults() {
        rule.setContent {
            FuzzyPickerDialog(
                title = "Pick", placeholder = "Type here",
                items = listOf(PickerItem("alpha") {}),
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Type here").performTextInput("zzzz")
        rule.onNodeWithText("No results").assertIsDisplayed()
    }

    @Test
    fun settingsTabWidthAndEditorThemeDialogs() {
        rule.setContent { SettingsPage(vm(), backStack()) }
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Tab width")), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.onNodeWithText("8").assertExists()
        rule.onNodeWithText("Close").performClick()
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Editor color theme")), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
    }
}
