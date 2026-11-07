package com.vayunmathur.files.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.HomeUiState
import com.vayunmathur.files.platform.StorageInfo
import com.vayunmathur.files.platform.TrashUiState
import com.vayunmathur.files.ui.dialogs.PropertiesDialog
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w800dp-h1280dp")
class HomeTrashScreenTest {

    @get:Rule val rule = createComposeRule()

    private fun item(name: String, dir: Boolean = false, size: Long? = 10, key: String = name) =
        FileBrowserItem(name, dir, size, File("/storage/emulated/0/$name"), null, key)

    @Test
    fun `home screen shows storage categories bookmarks and recents`() {
        val actions = RecordingFilesActions()
        val home = HomeUiState(
            rootDisplayName = "Pixel",
            storage = StorageInfo(totalBytes = 1000, freeBytes = 250),
            recents = listOf(item("recent.txt"), item("installer.apk")),
            bookmarks = listOf(item("Docs", dir = true)),
        )
        rule.setContent { HomeScreen(home = home, actions = actions) }
        rule.onNodeWithText("Files").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Internal storage").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Images").assertIsDisplayed()
        rule.onNodeWithText("Videos").assertIsDisplayed()
        rule.onNodeWithText("Audio").assertIsDisplayed()
        rule.onNodeWithText("Documents").assertIsDisplayed()
        rule.onNodeWithText("Downloads").assertIsDisplayed()
        rule.onNodeWithText("Docs").assertIsDisplayed()
        rule.onNodeWithText("recent.txt").assertIsDisplayed()
        rule.onNodeWithText("Images").performClick()
        assertTrue(actions.calls.contains("cat:IMAGES"))
        rule.onNodeWithText("recent.txt").performClick()
        assertTrue(actions.calls.contains("open:recent.txt"))
        rule.onNodeWithText("installer.apk").performClick()
        assertTrue(actions.calls.contains("apk:installer.apk"))
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Docs")), useUnmergedTree = true).performClick()
        assertTrue(actions.calls.contains("bookmark:Docs"))
        // "Internal storage" is both the storage-card title and the row; the row is second
        rule.onAllNodesWithText("Internal storage")[1].performClick()
        assertTrue(actions.calls.contains("storage"))
    }

    @Test
    fun `home screen without storage or lists still renders`() {
        val actions = RecordingFilesActions()
        rule.setContent { HomeScreen(home = HomeUiState(rootDisplayName = "Pixel"), actions = actions) }
        assertTrue(rule.onAllNodesWithText("Internal storage").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Trash").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Trash")), useUnmergedTree = true).performClick()
        assertTrue(actions.calls.contains("trash"))
    }

    @Test
    fun `trash screen empty state`() {
        val actions = RecordingFilesActions()
        rule.setContent { TrashScreen(state = TrashUiState(), actions = actions) }
        rule.onNodeWithText("Trash").assertIsDisplayed()
        rule.onNodeWithText("Trash is empty").assertIsDisplayed()
    }

    @Test
    fun `trash screen lists items and selection actions`() {
        val actions = RecordingFilesActions()
        val ghost = item("ghost.txt", key = "trash:1")
        val state = androidx.compose.runtime.mutableStateOf(
            TrashUiState(items = listOf(ghost)),
        )
        rule.setContent { TrashScreen(state = state.value, actions = actions) }
        rule.onNodeWithText("ghost.txt").assertIsDisplayed()
        // long press selects via toggle
        rule.onNodeWithText("ghost.txt").performTouchInput { down(center); advanceEventTime(700); up() }
        assertTrue(actions.calls.contains("trashToggle:ghost.txt"))
        // now render with a selection: restore / delete / clear buttons appear
        rule.runOnIdle { state.value = TrashUiState(items = listOf(ghost), selectedItems = setOf(ghost)) }
        // top-bar icon buttons (clear, restore, delete-forever) sit above the list;
        // pick them out by position rather than semantics-tree order
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val topBar = nodes.withIndex()
            .filter { it.value.positionInRoot.y < 100f }
            .sortedBy { it.value.positionInRoot.x }
        assertTrue(topBar.size >= 3)
        rule.onAllNodes(hasClickAction())[topBar[1].index].performClick()

        assertTrue(actions.calls.contains("trashRestore"))
        rule.onAllNodes(hasClickAction())[topBar[2].index].performClick()
        assertTrue(actions.calls.contains("trashDelete"))
    }

    @Test
    fun `trash empty confirm dialog empties trash`() {
        val actions = RecordingFilesActions()
        val ghost = item("ghost.txt", key = "trash:1")
        rule.setContent { TrashScreen(state = TrashUiState(items = listOf(ghost)), actions = actions) }
        // overflow (more-vert) is the right-most top-bar icon button
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val topBar = nodes.withIndex()
            .filter { it.value.positionInRoot.y < 100f }
            .sortedBy { it.value.positionInRoot.x }
        rule.onAllNodes(hasClickAction())[topBar.last().index].performClick()
        rule.onNodeWithText("Empty trash").assertIsDisplayed()
        rule.onNodeWithText("Empty trash").performClick()
        // confirmation dialog appears; its confirm also reads Delete forever
        rule.onNodeWithText("Delete forever").performClick()
        assertTrue(actions.calls.contains("trashEmpty"))
    }

    @Test
    fun `drawer lists categories and bookmarks`() {
        val actions = RecordingFilesActions()
        var closed = 0
        rule.setContent {
            FilesDrawer(
                bookmarks = listOf(item("Docs", dir = true)),
                actions = actions,
                closeDrawer = { closed++ },
            )
        }
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Images")), useUnmergedTree = true).performClick()
        assertTrue(actions.calls.contains("cat:IMAGES"))
        assertTrue(closed >= 1)
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Trash")), useUnmergedTree = true).performClick()
        assertTrue(actions.calls.contains("trash"))
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Docs")), useUnmergedTree = true).performClick()
        assertTrue(actions.calls.contains("bookmark:Docs"))
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText("Internal storage")), useUnmergedTree = true).performClick()
        assertTrue(actions.calls.contains("storage"))
    }

    @Test
    fun `properties dialog shows file facts`() {
        val f = item("report.pdf", size = 2048)
        var dismissed = false
        rule.setContent { PropertiesDialog(item = f, onDismiss = { dismissed = true }) }
        rule.onNodeWithText("report.pdf").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `parent pane lists siblings of current folder`() {
        val actions = RecordingFilesActions()
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val rootDir = File(ctx.cacheDir, "pane-${System.nanoTime()}").apply { mkdirs() }
        val docs = File(rootDir, "Docs").apply { mkdirs() }
        File(rootDir, "sibling.txt").writeText("sib")
        File(rootDir, "Other").mkdirs()
        File(rootDir, ".hidden-sibling").writeText("h")
        val state = androidx.compose.runtime.mutableStateOf(
            FilesUiState(
                rootDirectory = rootDir,
                rootDisplayName = "Pixel",
                currentDirectory = docs,
                directories = emptyList(),
                files = emptyList(),
            ),
        )
        rule.setContent { DirectoryParentPane(state = state.value, actions = actions) }
        rule.onNodeWithText("Pixel").assertIsDisplayed()
        rule.onNodeWithText("sibling.txt").assertIsDisplayed()
        rule.onNodeWithText("Other").assertIsDisplayed()
        // hidden sibling filtered by default
        assertTrue(rule.onAllNodesWithText(".hidden-sibling").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("sibling.txt").performClick()
        assertTrue(actions.calls.contains("open:sibling.txt"))
        rule.onNodeWithText("Other").performClick()
        assertTrue(actions.calls.contains("nav:Other"))
        // grid mode renders the same siblings
        rule.runOnIdle {
            state.value = state.value.copy(viewMode = com.vayunmathur.files.platform.ViewMode.GRID)
        }
        rule.onNodeWithText("sibling.txt").assertIsDisplayed()
        // at the root the pane is empty
        rule.runOnIdle { state.value = state.value.copy(currentDirectory = rootDir) }
        rule.waitForIdle()
        rootDir.deleteRecursively()
    }

    @Test
    fun `shell helper functions`() {
        val root = File("/a/b")
        val deep = File("/a/b/c/d")
        assertEquals(
            listOf("/a/b", "/a/b/c", "/a/b/c/d"),
            fileAncestors(deep, root).map { it.absolutePath },
        )
        assertTrue(fileAncestors(null, root).isEmpty())
        assertEquals(listOf("/a/b"), fileAncestors(root, root).map { it.absolutePath })
        // without an upTo bound, the chain runs to the filesystem root
        assertEquals("/", fileAncestors(deep, null).first().absolutePath)
    }
}
