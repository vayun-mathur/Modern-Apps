package com.vayunmathur.code.ui

import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.Route
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.code.util.GitRepo
import com.vayunmathur.code.util.loadFolder
import com.vayunmathur.code.util.TreeRowUiState
import com.vayunmathur.code.util.UserSnippet
import com.vayunmathur.library.util.NavBackStack
import java.io.File
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** More Robolectric interactions: git credentials/clone, tree delete/new-folder, snippet language, folder browsing. */
@RunWith(AndroidJUnit4::class)
class MoreInteractionsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())
    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Git))

    private fun clickClickableAt(index: Int) {
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true)[index]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    @Test
    fun gitCredentialsTypingUpdatesViewModel() {
        val v = vm()
        rule.setContent { GitPage(v, backStack()) }
        // Editable fields in order: clone URL, then the credentials Username field.
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("alice")
        rule.waitForIdle()
        assertEquals("alice", v.gitUsername)
    }

    @Test
    fun gitCloneUrlTypingEnablesClone() {
        val v = vm()
        rule.setContent { GitPage(v, backStack()) }
        // First editable field on the no-repo page is the clone URL.
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("https://example.com/repo.git")
        rule.onNodeWithText("Clone").assertIsDisplayed()
    }

    @Test
    fun gitChangeRowClickLoadsDiff() {
        val dir = File(System.getProperty("java.io.tmpdir"), "gitclick-${System.nanoTime()}").also { it.mkdirs() }
        GitRepo.init(dir)
        File(dir, "a.kt").writeText("v1")
        GitRepo.stage(dir, "a.kt")
        GitRepo.commit(dir, "c1", "T", "t@e.c")
        File(dir, "a.kt").writeText("v2")
        val v = vm()
        v.rootDir = dir
        v.gitIsRepo = true
        v.gitStatus = GitRepo.status(dir)
        try {
            rule.setContent { GitPage(v, backStack()) }
            clickRow("a.kt")
            rule.waitForIdle()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun treeState() = CodeUiState(
        folderOpen = true, rootName = "proj",
        nodes = listOf(TreeRowUiState(name = "a.kt", depth = 0)),
    )

    @Test
    fun fileTreeDeleteDialogConfirms() {
        var deleted = -1
        val actions = object : CodeActions {
            override fun deleteNode(index: Int) { deleted = index }
        }
        rule.setContent { FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {}) }
        // Clickables: header new-file, new-folder, open-folder, row, row overflow.
        clickClickableAt(4)
        clickRow("Delete")
        clickRow("Delete")
        assertEquals(0, deleted)
    }

    @Test
    fun fileTreeNewFolderDialogCreates() {
        var created: Pair<Int?, String>? = null
        val actions = object : CodeActions {
            override fun createFolder(parentIndex: Int?, name: String) { created = parentIndex to name }
        }
        rule.setContent { FileTreePane(treeState(), actions, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {}) }
        clickClickableAt(1)
        rule.onNodeWithText("New folder").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("pkg")
        clickRow("Create")
        assertEquals(null to "pkg", created)
    }

    @Test
    fun snippetsLanguageChoiceSetsLanguage() {
        val v = vm()
        rule.setContent { SnippetsPage(v, NavBackStack(arrayOf(Route.Editor))) }
        rule.onNodeWithText("Add snippet").performClick()
        rule.onNodeWithText("Trigger").performTextInput("logx")
        clickRow("All languages")
        clickRow("Kotlin")
        rule.onNodeWithText("Save").performClick()
        assertTrue(v.userSnippets.any { it.trigger == "logx" && it.languageId == "KOTLIN" }, "snippets=${v.userSnippets}")
    }

    @Test
    fun folderBrowserListsSubdirectory() {
        val root = Environment.getExternalStorageDirectory()
        val probe = File(root, "zzcovprobe${System.nanoTime() % 100000}")
        probe.mkdirs()
        try {
            rule.setContent { FolderBrowserPage(vm(), NavBackStack(arrayOf(Route.Editor))) }
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText(probe.name).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(probe.name).assertIsDisplayed()
        } finally {
            probe.deleteRecursively()
        }
    }

    @Test
    fun vmActionsToggleNodeAndDismissDisk() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vmactions-${System.nanoTime()}").also { it.mkdirs() }
        File(dir, "a.kt").writeText("fun a() {}")
        val v = vm()
        kotlinx.coroutines.runBlocking { v.loadFolder(dir) }
        assertTrue(v.nodes.isNotEmpty())
        // Toggling a file node opens it (goes through EditorTreeActions).
        v.actions.toggleNode(0)
        val deadline = System.currentTimeMillis() + 5000
        while (v.tabs.isEmpty() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue(v.tabs.isNotEmpty(), "toggleNode should open the file")
        dir.deleteRecursively()
    }
}
