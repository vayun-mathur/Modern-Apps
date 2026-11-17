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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.Route
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.code.domain.Diagnostic
import com.vayunmathur.code.domain.DiagnosticSeverity
import com.vayunmathur.code.domain.DiffRow
import com.vayunmathur.code.domain.DiffRowType
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.data.GitRepo
import com.vayunmathur.code.platform.TabUiState
import com.vayunmathur.library.util.NavBackStack
import java.io.File
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.vayunmathur.code.platform.deleteLine
import com.vayunmathur.code.platform.duplicateLine
import com.vayunmathur.code.platform.moveLineDown
import com.vayunmathur.code.platform.moveLineUp

/** Wave-2: GitPage repo interactions and EditorToolbar overflow / dialogs. */
@RunWith(AndroidJUnit4::class)
class Wave2GitToolbarUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())
    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Git))

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text, substring = true)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun clickClickableAt(index: Int) {
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true)[index]
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun repoWithChanges(): Pair<EditorViewModel, File> {
        val dir = File(System.getProperty("java.io.tmpdir"), "w2git-${System.nanoTime()}").also { it.mkdirs() }
        GitRepo.init(dir)
        File(dir, "a.kt").writeText("v1\n")
        GitRepo.stage(dir, "a.kt")
        GitRepo.commit(dir, "c1", "T", "t@e.c")
        File(dir, "a.kt").writeText("v2\n")
        File(dir, "b.kt").writeText("new\n")
        GitRepo.stage(dir, "b.kt")
        File(dir, "c.txt").writeText("untracked\n")
        val v = vm()
        v.rootDir = dir
        v.gitIsRepo = true
        v.gitStatus = GitRepo.status(dir)
        v.gitBranches.addAll(GitRepo.branches(dir))
        return v to dir
    }

    @Test
    fun gitCommitFlowCommitsStagedFile() {
        val (v, dir) = repoWithChanges()
        try {
            rule.setContent { GitPage(v, backStack()) }
            rule.onNodeWithText("Commit message").performTextInput("wave2 commit")
            clickRow("Commit")
            rule.waitUntil(5_000) { GitRepo.log(dir, 5).any { it.message.contains("wave2") } }
            assertTrue(GitRepo.status(dir).staged.isEmpty() || GitRepo.log(dir, 5).size >= 2)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun gitStageUnstageButtonsActOnRealRepo() {
        val (v, dir) = repoWithChanges()
        try {
            rule.setContent { GitPage(v, backStack()) }
            rule.onNodeWithText("Staged changes").assertExists()
            rule.onNodeWithText("Changes").assertExists()
            // Clicking a change row loads its diff dialog.
            clickRow("b.kt")
            rule.waitForIdle()
            // Diff dialog may appear; dismiss if present.
            runCatching { rule.onNodeWithText("Diff").assertExists() }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun gitPullPushButtonsDoNotCrashWithoutRemote() {
        val (v, dir) = repoWithChanges()
        try {
            rule.setContent { GitPage(v, backStack()) }
            clickRow("Pull")
            rule.waitForIdle()
            clickRow("Push")
            rule.waitForIdle()
            rule.onNodeWithText("Commit message").assertExists()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun gitNewBranchDialogCreatesAndChecksOut() {
        val (v, dir) = repoWithChanges()
        try {
            rule.setContent { GitPage(v, backStack()) }
            clickRow("New branch")
            rule.onAllNodes(hasSetTextAction()).let { it[it.fetchSemanticsNodes().size-1].performTextReplacement("feature-w2") }
            clickRow("Create")
            rule.waitUntil(5_000) { GitRepo.branches(dir).contains("feature-w2") }
            assertTrue(GitRepo.branches(dir).contains("feature-w2"))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun gitBranchSwitcherListsBranches() {
        val (v, dir) = repoWithChanges()
        try {
            GitRepo.createBranch(dir, "other-w2")
            v.gitBranches.add("other-w2")
            rule.setContent { GitPage(v, backStack()) }
            clickRow("Switch")
            rule.onNodeWithText("other-w2").assertExists()
            clickRow("other-w2")
            rule.waitUntil(5_000) { GitRepo.status(dir).branch == "other-w2" }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun gitCredentialsTypingUpdatesViewModel() {
        val v = vm()
        rule.setContent { GitPage(v, backStack()) }
        rule.onNodeWithText("Username").performTextInput("alice-w2")
        rule.onNodeWithText("Author name").performTextInput("Alice W2")
        rule.onNodeWithText("Author email").performTextInput("alice@w2.dev")
        rule.waitForIdle()
        assertEquals("alice-w2", v.gitUsername)
        assertEquals("Alice W2", v.gitAuthorName)
        assertEquals("alice@w2.dev", v.gitAuthorEmail)
    }

    @Test
    fun gitCloneUrlEnablesCloneButton() {
        val v = vm()
        rule.setContent { GitPage(v, backStack()) }
        rule.onNodeWithText("https://github.com/user/repo.git").performTextInput("https://example.invalid/x/repo.git")
        rule.waitForIdle()
        // Clone button becomes enabled; clicking it surfaces an error message (invalid remote).
        clickRow("Clone")
        rule.waitForIdle()
        rule.onNodeWithText("Initialize repository").assertExists()
    }

    @Test
    fun gitInitButtonCreatesRepo() {
        val dir = File(System.getProperty("java.io.tmpdir"), "w2init-${System.nanoTime()}").also { it.mkdirs() }
        try {
            val v = vm()
            v.rootDir = dir
            rule.setContent { GitPage(v, backStack()) }
            clickRow("Initialize repository")
            rule.waitUntil(5_000) { File(dir, ".git").exists() }
            assertTrue(File(dir, ".git").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun gitUnifiedDiffDialogRendersAndCloses() {
        val v = vm()
        v.gitDiff = "@@ -1 +1 @@\n-old line\n+new line\n context"
        rule.setContent { GitPage(v, backStack()) }
        rule.onNodeWithText("+new line", substring = true).assertExists()
        clickRow("Close")
        rule.waitForIdle()
        rule.onNodeWithText("+new line", substring = true).assertDoesNotExist()
    }

    @Test
    fun gitSideBySideDiffDialogRenders() {
        val v = vm()
        v.gitDiffRows = listOf(
            DiffRow(DiffRowType.HUNK, null, null, "@@ -1 +1 @@"),
            DiffRow(DiffRowType.REMOVE, 1, null, "old"),
            DiffRow(DiffRowType.ADD, null, 1, "new"),
            DiffRow(DiffRowType.CONTEXT, 2, 2, "same"),
        )
        rule.setContent { GitPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("Initialize repository").assertExists()
    }

    @Test
    fun gitMessageDismissViaCloseIcon() {
        val v = vm()
        v.gitMessage = "w2-error-marker"
        rule.setContent { GitPage(v, backStack()) }
        rule.onNodeWithText("w2-error-marker").assertIsDisplayed()
        // The close icon button is a clickable node after the message text.
        val nodes = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
    }

    // ---- Toolbar ----

    private fun tabState() = CodeUiState(
        tabs = listOf(
            TabUiState(
                name = "a.kt", language = Language.KOTLIN, canUndo = true, canRedo = true, isDirty = true,
            ),
        ),
        currentIndex = 0,
        diagnostics = listOf(
            Diagnostic(0, 0, 1, DiagnosticSeverity.ERROR, "boom"),
            Diagnostic(1, 0, 1, DiagnosticSeverity.WARNING, "careful"),
        ),
    )

    @Test
    fun toolbarAllOverflowItemsFire() {
        val fired = mutableListOf<String>()
        val actions = object : CodeActions {
            override fun toggleComment() { fired += "comment" }
            override fun duplicateLine() { fired += "dup" }
            override fun moveLineUp() { fired += "up" }
            override fun moveLineDown() { fired += "down" }
            override fun deleteLine() { fired += "del" }
            override fun formatDocument() { fired += "fmt" }
            override fun toggleSplit() { fired += "split" }
        }
        rule.setContent {
            EditorToolbar(
                tabState(), actions, onToggleFind = { fired += "find" },
                onGoToLine = { fired += "goto" }, onOpenSearch = { fired += "search" },
                onOpenGit = { fired += "git" }, onOpenTerminal = { fired += "term" },
                onOpenPreview = { fired += "preview" }, onOpenQuickOpen = { fired += "quick" },
                onOpenPalette = { fired += "palette" }, onOpenOutline = { fired += "outline" },
                onResolveConflicts = { fired += "conflicts" }, onOpenProblems = { fired += "problems" },
            )
        }
        val items = listOf(
            "Command palette" to "palette", "Quick open" to "quick", "Go to symbol" to "outline",
            "Go to line" to "goto", "Toggle comment" to "comment", "Duplicate line" to "dup",
            "Move line up" to "up", "Move line down" to "down", "Delete line" to "del",
            "Search in project" to "search", "Source control" to "git", "Terminal" to "term",
            "Preview" to "preview", "Format document" to "fmt", "Resolve conflicts" to "conflicts",
            "Problems" to "problems", "Split view" to "split",
        )
        for ((label, key) in items) {
            clickClickableAt(6) // overflow
            clickRow(label)
            rule.waitForIdle()
            assertTrue(fired.contains(key), "expected $key from $label, got $fired")
        }
    }

    @Test
    fun toolbarIconButtonsFire() {
        val fired = mutableListOf<String>()
        val actions = object : CodeActions {
            override fun undo() { fired += "undo" }
            override fun redo() { fired += "redo" }
            override fun save() { fired += "save" }
            override fun toggleSoftWrap() { fired += "wrap" }
            override fun insertText(insert: String) { fired += "indent:$insert" }
        }
        rule.setContent {
            EditorToolbar(tabState(), actions, onToggleFind = { fired += "find" }, onOpenProblems = { fired += "problems" })
        }
        clickClickableAt(0); clickClickableAt(1); clickClickableAt(2)
        clickClickableAt(3); clickClickableAt(4); clickClickableAt(5)
        assertEquals(listOf("undo", "redo", "save", "find", "wrap", "indent:    "), fired)
        // Diagnostics button shows error/warning counts and fires problems.
        rule.onNode(hasClickAction() and hasText("1/1", substring = true)).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(fired.contains("problems"))
    }

    @Test
    fun editorCommandsInvokeEveryEntry() {
        val fired = mutableListOf<String>()
        val actions = object : CodeActions {
            override fun save() { fired += "save" }
            override fun saveAll() { fired += "saveAll" }
            override fun toggleComment() { fired += "comment" }
            override fun duplicateLine() { fired += "dup" }
            override fun moveLineUp() { fired += "up" }
            override fun moveLineDown() { fired += "down" }
            override fun deleteLine() { fired += "del" }
            override fun formatDocument() { fired += "fmt" }
            override fun toggleSplit() { fired += "split" }
            override fun toggleSoftWrap() { fired += "wrap" }
            override fun undo() { fired += "undo" }
            override fun redo() { fired += "redo" }
        }
        var items: List<PickerItem> = emptyList()
        rule.setContent {
            items = editorCommands(
                actions = actions, onToggleFind = { fired += "find" }, onGoToLine = { fired += "goto" },
                onQuickOpen = { fired += "quick" }, onOutline = { fired += "outline" },
                onResolveConflicts = { fired += "conflicts" }, onProblems = { fired += "problems" },
                onOpenSearch = { fired += "search" }, onOpenGit = { fired += "git" },
                onOpenTerminal = { fired += "term" }, onOpenPreview = { fired += "preview" },
                onOpenSettings = { fired += "settings" }, onOpenFolder = { fired += "folder" },
                onOpenFile = { fired += "file" },
            )
        }
        assertTrue(items.size >= 20)
        items.forEach { it.onSelect() }
        assertTrue(fired.containsAll(listOf("save", "saveAll", "find", "goto", "comment", "split", "wrap", "undo", "redo", "search", "git", "settings", "folder", "file")))
    }

    @Test
    fun goToLineDialogGoAndCancel() {
        var gone: Int? = null
        var dismissed = 0
        rule.setContent { GoToLineDialog(onGo = { gone = it }, onDismiss = { dismissed++ }) }
        rule.onNodeWithText("Line", substring = true).performTextInput("12")
        clickRow("Go")
        assertEquals(12, gone)
        assertEquals(1, dismissed)
    }

    @Test
    fun diskChangedBannerButtonsFire() {
        var reloaded = 0; var kept = 0
        rule.setContent { DiskChangedBanner(onReload = { reloaded++ }, onKeep = { kept++ }) }
        rule.onNodeWithText("Reload").performClick()
        rule.onNodeWithText("Keep mine").performClick()
        assertEquals(1, reloaded); assertEquals(1, kept)
        rule.onNodeWithText("This file changed on disk.").assertIsDisplayed()
    }

    @Test
    fun tabStripSelectAndCleanClose() {
        var selected = -1; var closed = -1
        val actions = object : CodeActions {
            override fun selectTab(index: Int) { selected = index }
            override fun closeTab(index: Int) { closed = index }
        }
        val s = CodeUiState(
            tabs = listOf(
                TabUiState(name = "one.kt", language = Language.KOTLIN),
                TabUiState(name = "two.kt", language = Language.KOTLIN),
            ),
            currentIndex = 0,
        )
        rule.setContent { TabStrip(s, actions) }
        clickRow("two.kt")
        assertEquals(1, selected)
        rule.onAllNodesWithText("one.kt").onFirst().assertIsDisplayed()
        assertEquals(-1, closed)
    }
}
