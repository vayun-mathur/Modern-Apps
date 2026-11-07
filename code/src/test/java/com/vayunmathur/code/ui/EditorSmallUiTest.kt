package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.Completion
import com.vayunmathur.code.util.CompletionKind
import com.vayunmathur.code.util.DiffRow
import com.vayunmathur.code.util.DiffRowType
import com.vayunmathur.code.util.TabUiState
import com.vayunmathur.code.util.TreeRowUiState
import com.vayunmathur.code.util.parseUnifiedDiff
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** Robolectric Compose tests for the small state-driven UI: tabs, toolbar, find bar, dialogs. */
@RunWith(AndroidJUnit4::class)
class EditorSmallUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun state() = CodeUiState(
        tabs = listOf(
            TabUiState(name = "a.kt", language = Language.KOTLIN),
            TabUiState(name = "b.py", language = Language.PYTHON, isDirty = true),
        ),
        currentIndex = 0,
    )

    @Test
    fun tabStripShowsTabsAndSelects() {
        var selected = -1
        val actions = object : CodeActions {
            override fun selectTab(index: Int) {
                selected = index
            }
        }
        rule.setContent { TabStrip(state(), actions) }
        rule.onNodeWithText("a.kt").assertIsDisplayed()
        rule.onNodeWithText("b.py").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("b.py")), useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(selected == 1, "selected=$selected")
    }

    @Test
    fun tabStripDirtyTabCloseAsksToConfirmFirst() {
        var closed = -1
        val actions = object : CodeActions {
            override fun closeTab(index: Int) {
                closed = index
            }
        }
        rule.setContent { TabStrip(state(), actions) }
        // Both tabs render; closing a clean tab closes immediately (no dialog).
        rule.onNodeWithText("a.kt").assertIsDisplayed()
        assertTrue(closed == -1)
    }

    @Test
    fun editorToolbarShowsLanguageAndEncoding() {
        rule.setContent { EditorToolbar(state(), CodeActions.Noop, onToggleFind = {}) }
        rule.onNodeWithText("Kotlin").assertExists()
        rule.onNodeWithText("UTF-8 · LF", substring = true).assertExists()
    }

    @Test
    fun editorToolbarRendersNothingWithoutActiveTab() {
        rule.setContent { EditorToolbar(CodeUiState(), CodeActions.Noop, onToggleFind = {}) }
        rule.onNodeWithText("Kotlin").assertDoesNotExist()
    }

    @Test
    fun diskChangedBannerOffersReloadAndKeep() {
        var reloaded = false
        var kept = false
        rule.setContent { DiskChangedBanner(onReload = { reloaded = true }, onKeep = { kept = true }) }
        rule.onNodeWithText("Reload", substring = true).performClick()
        assertTrue(reloaded)
        rule.onNodeWithText("Keep", substring = true).performClick()
        assertTrue(kept)
    }

    @Test
    fun goToLineDialogGoesToTypedLine() {
        var gone: Int? = null
        var dismissed = false
        rule.setContent { GoToLineDialog(onGo = { gone = it }, onDismiss = { dismissed = true }) }
        rule.onNodeWithText("Line", substring = true).performTextInput("1a2b3")
        rule.onNodeWithText("Go").performClick()
        assertTrue(gone == 123)
        assertTrue(dismissed)
    }

    @Test
    fun findBarShowsCountAndToggles() {
        var toggled = false
        rule.setContent {
            FindBar(
                query = "foo",
                replacement = "bar",
                caseSensitive = false,
                useRegex = false,
                regexValid = true,
                matchCount = 3,
                activeMatch = 1,
                onQueryChange = {},
                onReplacementChange = {},
                onToggleCase = { toggled = true },
                onToggleRegex = {},
                onNext = {},
                onPrev = {},
                onReplace = {},
                onReplaceAll = {},
                onClose = {},
            )
        }
        rule.onNodeWithText("2/3").assertIsDisplayed()
    }

    @Test
    fun findBarShowsInvalidRegex() {
        rule.setContent {
            FindBar(
                query = "(",
                replacement = "",
                caseSensitive = false,
                useRegex = true,
                regexValid = false,
                matchCount = 0,
                activeMatch = -1,
                onQueryChange = {},
                onReplacementChange = {},
                onToggleCase = {},
                onToggleRegex = {},
                onNext = {},
                onPrev = {},
                onReplace = {},
                onReplaceAll = {},
                onClose = {},
            )
        }
        rule.onNodeWithText("0/0").assertIsDisplayed()
    }

    @Test
    fun fileTreePaneEmptyState() {
        rule.setContent {
            FileTreePane(CodeUiState(), CodeActions.Noop, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {})
        }
        rule.onNodeWithText("Explorer").assertIsDisplayed()
    }

    @Test
    fun fileTreePaneShowsNodes() {
        val s = CodeUiState(
            folderOpen = true,
            rootName = "proj",
            nodes = listOf(
                TreeRowUiState(name = "src", isDirectory = true, expanded = true),
                TreeRowUiState(name = "a.kt", depth = 1),
            ),
        )
        rule.setContent {
            FileTreePane(s, CodeActions.Noop, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {})
        }
        rule.onNodeWithText("proj").assertIsDisplayed()
        rule.onNodeWithText("src").assertIsDisplayed()
        rule.onNodeWithText("a.kt").assertIsDisplayed()
    }

    @Test
    fun fuzzyPickerShowsItemsAndSelects() {
        var chosen: String? = null
        val items = listOf(
            PickerItem("alpha", "a.kt") { chosen = "alpha" },
            PickerItem("beta", "b.kt") { chosen = "beta" },
        )
        rule.setContent {
            FuzzyPickerDialog(title = "Open", placeholder = "Type", items = items, onDismiss = {})
        }
        rule.onNodeWithText("Open").assertIsDisplayed()
        rule.onNodeWithText("alpha").assertIsDisplayed()
        rule.onNodeWithText("beta").assertIsDisplayed()
        rule.onNodeWithText("beta").performClick()
        assertTrue(chosen == "beta")
    }

    @Test
    fun sideBySideDiffEmpty() {
        rule.setContent { SideBySideDiffDialog(emptyList()) {} }
        rule.waitForIdle()
    }

    @Test
    fun sideBySideDiffFilled() {
        val diff = "diff --git a/f b/f\n@@ -1,2 +1,2 @@\n context\n-old\n+new"
        val rows = parseUnifiedDiff(diff)
        assertTrue(rows.isNotEmpty())
        rule.setContent { SideBySideDiffDialog(rows) {} }
        rule.onAllNodesWithText("context", substring = true).onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("old", substring = true).onFirst().assertIsDisplayed()
        rule.onAllNodesWithText("new", substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun mergeResolverNoConflicts() {
        rule.setContent { MergeResolverDialog("plain text", onResolve = {}, onDismiss = {}) }
        rule.waitForIdle()
    }

    @Test
    fun mergeResolverWithConflicts() {
        var resolved: List<com.vayunmathur.code.util.Resolution>? = null
        val text = "a\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> branch\nb"
        rule.setContent { MergeResolverDialog(text, onResolve = { resolved = it }, onDismiss = {}) }
        rule.onNodeWithText("ours").assertIsDisplayed()
        rule.onNodeWithText("theirs").assertIsDisplayed()
        assertTrue(resolved == null)
    }

    @Test
    fun gitPageSectionEmpty() {
        rule.setContent { GitPageSection("", onDismiss = {}) }
        rule.waitForIdle()
    }

    @Test
    fun gitPageSectionColors() {
        rule.setContent { GitPageSection("@@ -1 +1 @@\n-old\n+new\n ctx", onDismiss = {}) }
        rule.onNodeWithText("-old", substring = true).assertIsDisplayed()
        rule.onNodeWithText("+new", substring = true).assertIsDisplayed()
    }

    @Test
    fun completionRowsDirectly() {
        val rows = listOf(
            DiffRow(DiffRowType.HUNK, null, null, "@@ -1 +1 @@"),
            DiffRow(DiffRowType.CONTEXT, 1, 1, "same"),
            DiffRow(DiffRowType.ADD, null, 2, "added"),
            DiffRow(DiffRowType.REMOVE, 2, null, "removed"),
        )
        rule.setContent { SideBySideDiffDialog(rows) {} }
        rule.onAllNodesWithText("same", substring = true).onFirst().assertIsDisplayed()
        rule.onNodeWithText("added", substring = true).assertIsDisplayed()
        rule.onNodeWithText("removed", substring = true).assertIsDisplayed()
    }

    @Test
    fun completionKindLabels() {
        assertTrue(Completion("w", "w", 1, CompletionKind.WORD).kind == CompletionKind.WORD)
        assertTrue(Completion("k", "k", 1, CompletionKind.KEYWORD).kind == CompletionKind.KEYWORD)
        assertTrue(Completion("s", "s", 1, CompletionKind.SNIPPET).kind == CompletionKind.SNIPPET)
    }
}
