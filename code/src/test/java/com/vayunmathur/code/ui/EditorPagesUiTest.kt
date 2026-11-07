package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.Route
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.code.util.TabUiState
import com.vayunmathur.code.util.UserSnippet
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** Robolectric Compose tests for the page-level screens (settings, snippets, body, minimap). */
@RunWith(AndroidJUnit4::class)
class EditorPagesUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Editor))

    @Test
    fun settingsPageShowsSections() {
        rule.setContent { SettingsPage(vm(), backStack()) }
        rule.onNodeWithText("Appearance", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Editor", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun settingsPageOpensThemeDialog() {
        rule.setContent { SettingsPage(vm(), backStack()) }
        // Tapping the theme row opens a choice dialog.
        rule.onAllNodesWithText("System", substring = true).assertCountEquals(1)
        rule.onNodeWithText("Theme", substring = true).performClick()
        // A dialog with theme choices appears (adds a second "System" node).
        rule.onAllNodesWithText("System", substring = true).assertCountEquals(2)
    }

    @Test
    fun snippetsPageEmptyStateAndAdd() {
        val v = vm()
        rule.setContent { SnippetsPage(v, backStack()) }
        rule.onNodeWithText("No snippets yet. Add one to expand it while you type.").assertIsDisplayed()
        rule.onNodeWithText("Add snippet").assertIsDisplayed()
    }

    @Test
    fun snippetsPageListsSnippets() {
        val v = vm()
        v.userSnippets.add(UserSnippet("log", "console.log($0)"))
        rule.setContent { SnippetsPage(v, backStack()) }
        rule.onNodeWithText("log").assertIsDisplayed()
    }

    @Test
    fun emptyEditorStateRenders() {
        var folder = false
        var file = false
        rule.setContent { EmptyEditorState(onOpenFolder = { folder = true }, onOpenFile = { file = true }) }
        rule.onNodeWithText("No file open").assertIsDisplayed()
        rule.onNodeWithText("Open folder").performClick()
        assertTrue(folder)
        rule.onNodeWithText("Open file").performClick()
        assertTrue(file)
    }

    @Test
    fun paneHeaderShowsName() {
        rule.setContent { PaneHeader(name = "a.kt", onClose = {}) }
        rule.onNodeWithText("a.kt").assertIsDisplayed()
    }

    @Test
    fun editorBodyEmptyState() {
        var folder = false
        rule.setContent {
            EditorBody(
                state = CodeUiState(),
                actions = com.vayunmathur.code.util.CodeActions.Noop,
                onOpenFolder = { folder = true },
                onOpenFile = {},
                onOpenSearch = {},
                onOpenGit = {},
                onOpenTerminal = {},
                onOpenPreview = {},
                onOpenQuickOpen = {},
                onOpenPalette = {},
                onOpenOutline = {},
                onResolveConflicts = {},
                onOpenProblems = {},
                onToggleFind = {},
                onGoToLine = {},
                showFind = false,
                onCloseFind = {},
                initialFind = null,
            )
        }
        rule.onNodeWithText("No file open").assertIsDisplayed()
        rule.onNodeWithText("Open folder").performClick()
        assertTrue(folder)
    }

    @Test
    fun editorBodyWithTabShowsToolbar() {
        val s = CodeUiState(
            tabs = listOf(TabUiState(name = "a.kt", language = com.vayunmathur.code.syntax.Language.KOTLIN)),
            currentIndex = 0,
        )
        rule.setContent {
            EditorBody(
                state = s,
                actions = com.vayunmathur.code.util.CodeActions.Noop,
                onOpenFolder = {},
                onOpenFile = {},
                onOpenSearch = {},
                onOpenGit = {},
                onOpenTerminal = {},
                onOpenPreview = {},
                onOpenQuickOpen = {},
                onOpenPalette = {},
                onOpenOutline = {},
                onResolveConflicts = {},
                onOpenProblems = {},
                onToggleFind = {},
                onGoToLine = {},
                showFind = true,
                onCloseFind = {},
                initialFind = null,
            )
        }
        rule.onNodeWithText("a.kt").assertIsDisplayed()
    }

    @Test
    fun terminalPageWithoutFolderShowsHint() {
        rule.setContent { TerminalPage(vm(), backStack()) }
        rule.onNodeWithText("Open a folder to use the terminal.").assertIsDisplayed()
    }

    @Test
    fun previewPageWithoutTabShowsHint() {
        rule.setContent { PreviewPage(vm(), backStack()) }
        rule.onNodeWithText("Open a file to preview.").assertIsDisplayed()
    }

    @Test
    fun minimapRenders() {
        rule.setContent {
            Minimap(
                lines = listOf("fun f() {", "  x", "}"),
                lineHeight = 10f,
                scrollY = 0f,
                maxScrollY = 100f,
                viewportHeight = 50f,
                totalHeight = 200f,
                color = androidx.compose.ui.graphics.Color.Gray,
                viewportColor = androidx.compose.ui.graphics.Color.Blue,
                onScrollTo = {},
            )
        }
        // Canvas has no text nodes; reaching composition without crashing is the assertion.
    }

    @Test
    fun codeWideLayoutRendersSlots() {
        rule.setContent {
            CodeWideLayout(
                tree = { m -> androidx.compose.foundation.layout.Box(m) { onTreeSlot() } },
                editor = { m -> androidx.compose.foundation.layout.Box(m) { onEditorSlot() } },
                terminal = { m -> androidx.compose.foundation.layout.Box(m) { onTerminalSlot() } },
            )
        }
        rule.onNodeWithText("tree-slot").assertIsDisplayed()
        rule.onNodeWithText("editor-slot").assertIsDisplayed()
        rule.onNodeWithText("terminal-slot").assertIsDisplayed()
    }

    @Test
    fun codeWideLayoutHidesTerminal() {
        rule.setContent {
            CodeWideLayout(
                tree = { m -> androidx.compose.foundation.layout.Box(m) {} },
                editor = { m -> androidx.compose.foundation.layout.Box(m) {} },
                terminal = { m -> androidx.compose.foundation.layout.Box(m) { onTerminalSlot() } },
                showTerminal = false,
            )
        }
        rule.onNodeWithText("terminal-slot").assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun onTreeSlot() {
        androidx.compose.material3.Text("tree-slot")
    }

    @androidx.compose.runtime.Composable
    private fun onEditorSlot() {
        androidx.compose.material3.Text("editor-slot")
    }

    @androidx.compose.runtime.Composable
    private fun onTerminalSlot() {
        androidx.compose.material3.Text("terminal-slot")
    }
}
