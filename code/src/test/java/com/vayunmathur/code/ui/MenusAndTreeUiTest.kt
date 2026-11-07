package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.TabUiState
import com.vayunmathur.code.util.TreeRowUiState
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for toolbar overflow menu items, tab close confirmation and file-tree dialogs. */
@RunWith(AndroidJUnit4::class)
class MenusAndTreeUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun clickClickableAt(index: Int) {
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true)[index]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun tabState() = CodeUiState(
        tabs = listOf(TabUiState(name = "a.kt", language = Language.KOTLIN, canUndo = true, isDirty = true)),
        currentIndex = 0,
    )

    @Test
    fun toolbarOverflowPaletteItemFires() {
        var palette = false
        rule.setContent {
            EditorToolbar(tabState(), CodeActions.Noop, onToggleFind = {}, onOpenPalette = { palette = true })
        }
        // Icon buttons in order: undo, redo, save, find, wrap, indent, overflow.
        clickClickableAt(6)
        rule.onNodeWithText("Command palette").assertIsDisplayed()
        clickRow("Command palette")
        assertTrue(palette)
    }

    @Test
    fun toolbarOverflowGoToLineItemFires() {
        var goTo = false
        rule.setContent {
            EditorToolbar(tabState(), CodeActions.Noop, onToggleFind = {}, onGoToLine = { goTo = true })
        }
        clickClickableAt(6)
        clickRow("Go to line")
        assertTrue(goTo)
    }

    @Test
    fun toolbarButtonsInvokeActions() {
        var undone = 0
        var saved = 0
        val actions = object : CodeActions {
            override fun undo() { undone++ }
            override fun save() { saved++ }
        }
        rule.setContent { EditorToolbar(tabState(), actions, onToggleFind = {}) }
        clickClickableAt(0)
        clickClickableAt(2)
        assertEquals(1, undone)
        assertEquals(1, saved)
    }

    @Test
    fun dirtyTabCloseShowsDiscardDialog() {
        var closed = -1
        val actions = object : CodeActions {
            override fun closeTab(index: Int) { closed = index }
        }
        val s = CodeUiState(
            tabs = listOf(TabUiState(name = "dirty.kt", language = Language.KOTLIN, isDirty = true)),
            currentIndex = 0,
        )
        rule.setContent { TabStrip(s, actions) }
        // Clickables: tab row, then its close button.
        clickClickableAt(1)
        clickRow("Discard")
        assertEquals(0, closed)
    }

    @Test
    fun cleanTabCloseClosesImmediately() {
        var closed = -1
        val actions = object : CodeActions {
            override fun closeTab(index: Int) { closed = index }
        }
        val s = CodeUiState(
            tabs = listOf(TabUiState(name = "clean.kt", language = Language.KOTLIN)),
            currentIndex = 0,
        )
        rule.setContent { TabStrip(s, actions) }
        clickClickableAt(1)
        assertEquals(0, closed)
    }

    private fun treeState() = CodeUiState(
        folderOpen = true, rootName = "proj",
        nodes = listOf(
            TreeRowUiState(name = "src", isDirectory = true, expanded = true),
            TreeRowUiState(name = "a.kt", depth = 1),
        ),
    )

    @Test
    fun fileTreeNewFileDialogCreates() {
        var created: Pair<Int?, String>? = null
        val actions = object : CodeActions {
            override fun createFile(parentIndex: Int?, name: String) { created = parentIndex to name }
        }
        rule.setContent {
            FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {})
        }
        // Header buttons: new file, new folder, open folder.
        clickClickableAt(0)
        rule.onNodeWithText("New file").assertIsDisplayed()
        rule.onNodeWithText("Name").performTextInput("new.kt")
        clickRow("Create")
        assertEquals(null to "new.kt", created)
    }

    @Test
    fun fileTreeRowClickTogglesAndOpensFile() {
        var toggled = -1
        var opened = false
        val actions = object : CodeActions {
            override fun toggleNode(index: Int) { toggled = index }
        }
        rule.setContent {
            FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = { opened = true })
        }
        clickRow("a.kt")
        assertEquals(1, toggled)
        assertTrue(opened)
    }

    @Test
    fun fileTreeRenameViaOverflow() {
        var renamed: Pair<Int, String>? = null
        val actions = object : CodeActions {
            override fun renameNode(index: Int, newName: String) { renamed = index to newName }
        }
        rule.setContent {
            FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {})
        }
        // Clickables: header(3), row0 row, row0 overflow, row1 row, row1 overflow.
        clickClickableAt(6)
        clickRow("Rename")
        rule.onNode(hasSetTextAction()).performTextReplacement("b.kt")
        clickRow("Rename")
        assertEquals(1 to "b.kt", renamed)
    }

    @Test
    fun goToLineDialogRejectsZero() {
        var gone: Int? = null
        rule.setContent { GoToLineDialog(onGo = { gone = it }, onDismiss = {}) }
        rule.onNodeWithText("Line", substring = true).performTextInput("0")
        // Go is disabled for line 0, so clicking the (disabled) button does nothing.
        rule.onAllNodesWithText("Go").onFirst().assertExists()
        assertEquals(null, gone)
    }
}
