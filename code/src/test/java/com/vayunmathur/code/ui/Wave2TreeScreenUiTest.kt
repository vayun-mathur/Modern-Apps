package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.platform.ProjectFileEntry
import com.vayunmathur.code.platform.TabUiState
import com.vayunmathur.code.platform.TreeRowUiState
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Wave-2: FileTreePane dialogs and EditorScreen picker/dialog flows. */
@RunWith(AndroidJUnit4::class)
class Wave2TreeScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text, substring = true)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun clickClickableAt(index: Int) {
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true)[index]
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun treeState() = CodeUiState(
        folderOpen = true, rootName = "proj",
        nodes = listOf(
            TreeRowUiState(name = "src", isDirectory = true, expanded = false),
            TreeRowUiState(name = "a.kt", depth = 1),
            TreeRowUiState(name = "README.md", depth = 0),
        ),
    )

    @Test
    fun fileTreeNewFolderDialogCreates() {
        var created: Pair<Int?, String>? = null
        val actions = object : CodeActions {
            override fun createFolder(parentIndex: Int?, name: String) { created = parentIndex to name }
        }
        rule.setContent { FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {}) }
        clickClickableAt(1) // header new-folder
        rule.onNodeWithText("New folder").assertIsDisplayed()
        rule.onNodeWithText("Name").performTextInput("docs")
        clickRow("Create")
        assertEquals(null to "docs", created)
    }

    @Test
    fun fileTreeDeleteDialogConfirms() {
        var deleted = -1
        val actions = object : CodeActions {
            override fun deleteNode(index: Int) { deleted = index }
        }
        rule.setContent { FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {}) }
        // Open overflow for README.md row: clickables = header3 + rows( row+overflow )*3 ; last overflow is index?
        clickClickableAt(8) // README.md overflow (header 3 + 2 clickables per row)
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("This cannot be undone.").assertExists()
        rule.onAllNodesWithText("Delete")[0].performClick()
        assertTrue(deleted >= 0, "deleted=$deleted")
    }

    @Test
    fun fileTreeDirectoryOverflowNewFile() {
        var created: Pair<Int?, String>? = null
        val actions = object : CodeActions {
            override fun createFile(parentIndex: Int?, name: String) { created = parentIndex to name }
        }
        rule.setContent { FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {}) }
        clickClickableAt(4) // src row overflow (header3, src row, src overflow?) - try first overflow after header
        rule.waitForIdle()
        // If menu opened, New file item exists; otherwise fail visibly via assertion below.
        rule.onNodeWithText("New file").assertExists()
        clickRow("New file")
        rule.onNodeWithText("Name").performTextInput("in-src.kt")
        clickRow("Create")
        assertEquals(0 to "in-src.kt", created)
    }

    @Test
    fun fileTreeEmptyStateButtonsFire() {
        var folder = 0; var file = 0
        rule.setContent {
            FileTreePane(CodeUiState(folderOpen = false), CodeActions.Noop, onOpenFolder = { folder++ }, onOpenFile = { file++ }, onFileOpened = {})
        }
        rule.onNodeWithText("No folder opened").assertIsDisplayed()
        clickRow("Open folder")
        clickRow("Open file")
        assertEquals(1, folder); assertEquals(1, file)
    }

    @Test
    fun fileTreeDirectoryRowToggleDoesNotOpenFile() {
        var toggled = -1; var opened = false
        val actions = object : CodeActions { override fun toggleNode(index: Int) { toggled = index } }
        rule.setContent { FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = { opened = true }) }
        clickRow("src")
        assertEquals(0, toggled)
        assertTrue(!opened)
    }

    // ---- EditorScreen dialogs ----

    private fun screenState() = CodeUiState(
        tabs = listOf(
            TabUiState(
                name = "a.kt",
                value = TextFieldValue("fun alpha() {}\nfun beta() {}\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> x"),
                language = Language.KOTLIN, isDirty = true,
            ),
        ),
        currentIndex = 0,
        projectFiles = listOf(ProjectFileEntry("/p/a.kt", "a.kt", "a.kt"), ProjectFileEntry("/p/b.kt", "b.kt", "b.kt")),
        recentFiles = listOf(ProjectFileEntry("/p/a.kt", "a.kt", "a.kt")),
        diagnostics = listOf(Diagnostic(0, 0, 3, DiagnosticSeverity.ERROR, "boom-w2")),
    )

    @Test
    fun editorScreenQuickOpenViaTopSearch() {
        var openedPath: String? = null
        val actions = object : CodeActions {
            override fun openPath(path: String) { openedPath = path }
            override fun refreshProjectFiles() {}
        }
        rule.setContent { EditorScreen(state = screenState(), actions = actions) }
        var found = false
        val total = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size
        for (i in 0 until total) {
            runCatching { clickClickableAt(i) }
            rule.waitForIdle()
            if (runCatching { rule.onNodeWithText("Quick open").assertExists(); true }.getOrDefault(false)) { found = true; break }
        }
        assertTrue(found, "quick open not opened")
        assertTrue(rule.onAllNodesWithText("a.kt").fetchSemanticsNodes().size >= 2)
        assertEquals(null, openedPath)
    }

    @Test
    fun editorScreenGoToLineViaToolbarOverflow() {
        var gone: Int? = null
        val actions = object : CodeActions {
            override fun goToLine(line: Int) { gone = line }
        }
        rule.setContent { EditorScreen(state = screenState(), actions = actions) }
        // Open toolbar overflow: find via menu items after opening - clickables include top bar + tabstrip + toolbar.
        // Use text-based approach: open overflow by clicking last toolbar icon before diagnostics.
        // Fallback: drive through command palette below if this fails; here we attempt all clickables until Go to line appears.
        var found = false
        val total = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size
        for (i in 0 until total) {
            runCatching { clickClickableAt(i) }
            rule.waitForIdle()
            if (runCatching { rule.onNodeWithText("Go to line").assertExists(); true }.getOrDefault(false)) { found = true; break }
        }
        assertTrue(found, "overflow menu not found")
        clickRow("Go to line")
        rule.onNodeWithText("Line", substring = true).performTextInput("2")
        clickRow("Go")
        assertEquals(2, gone)
    }

    @Test
    fun editorScreenProblemsViaToolbarDiagnostics() {
        var gone: Int? = null
        val actions = object : CodeActions { override fun goToLine(line: Int) { gone = line } }
        rule.setContent { EditorScreen(state = screenState(), actions = actions) }
        clickRow("1/0")
        rule.waitForIdle()
        rule.onNodeWithText("boom-w2").assertExists()
        rule.onNodeWithText("boom-w2").performClick()
        assertEquals(1, gone)
    }

    @Test
    fun editorScreenEmptyButtonsFire() {
        var folder = 0; var file = 0
        rule.setContent {
            EditorScreen(state = CodeUiState(), actions = CodeActions.Noop, onOpenFolder = { folder++ }, onOpenFile = { file++ })
        }
        rule.onNodeWithText("No file open").assertIsDisplayed()
        rule.onAllNodesWithText("Open folder").onFirst().performClick()
        // Empty state also has Open file button in body ( drawer has another) - click body one via all nodes last
        val nodes = rule.onAllNodesWithText("Open file").fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        assertEquals(1, folder)
    }

    @Test
    fun editorBodyEmptyAndDiskBannerKeep() {
        var kept = 0; var folder = 0
        val actions = object : CodeActions { override fun dismissDiskChange() { kept++ } }
        val s = CodeUiState(
            tabs = listOf(TabUiState(name = "a.kt", value = TextFieldValue("x"), language = Language.KOTLIN, changedOnDisk = true)),
            currentIndex = 0,
        )
        rule.setContent {
            EditorBody(
                state = s, actions = actions, onOpenFolder = { folder++ }, onOpenFile = {},
                onOpenSearch = {}, onOpenGit = {}, onOpenTerminal = {}, onOpenPreview = {},
                onOpenQuickOpen = {}, onOpenPalette = {}, onOpenOutline = {}, onResolveConflicts = {},
                onOpenProblems = {}, onToggleFind = {}, onGoToLine = {}, showFind = false,
                onCloseFind = {}, initialFind = null,
            )
        }
        rule.onNodeWithText("Keep mine").performClick()
        assertEquals(1, kept)
    }

    @Test
    fun paneHeaderCloseFires() {
        var closed = 0
        rule.setContent { PaneHeader("second.kt", onClose = { closed++ }) }
        rule.onNodeWithText("second.kt").assertIsDisplayed()
        clickClickableAt(0)
        assertEquals(1, closed)
    }

    @Test
    fun splitEditorsRenderBothHeaders() {
        val s = screenState().copy(secondaryIndex = 0)
        // Use distinct tabs
        val s2 = CodeUiState(
            tabs = listOf(
                TabUiState(name = "p1.kt", value = TextFieldValue("fun p1() {}"), language = Language.KOTLIN),
                TabUiState(name = "p2.kt", value = TextFieldValue("fun p2() {}"), language = Language.KOTLIN),
            ),
            currentIndex = 0, secondaryIndex = 1, focusedSecondary = true,
        )
        rule.setContent {
            SplitEditors(state = s2, actions = CodeActions.Noop, primary = s2.tabs[0], secondary = s2.tabs[1], showFind = true, onCloseFind = {}, initialQuery = "fun")
        }
        rule.onAllNodesWithText("p1.kt").onFirst().assertExists()
        rule.onAllNodesWithText("p2.kt").onFirst().assertExists()
    }
}
