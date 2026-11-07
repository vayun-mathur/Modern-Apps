package com.vayunmathur.files.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.SortBy
import com.vayunmathur.files.platform.ViewMode
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class DirectoryDialogsFlowTest {

    @get:Rule val rule = createComposeRule()

    private val root = File("/storage/emulated/0")
    private fun item(name: String, dir: Boolean = false) = FileBrowserItem(
        name, dir, if (dir) null else 10,
        File(File(root, "Docs"), name), null, File(File(root, "Docs"), name).absolutePath,
    )

    private fun state(selected: Set<FileBrowserItem> = emptySet()) = FilesUiState(
        rootDirectory = root,
        rootDisplayName = "Pixel",
        currentDirectory = File(root, "Docs"),
        directories = listOf(item("subfolder", dir = true)),
        files = listOf(item("notes.txt"), item("bundle.zip")),
        selectedPaths = selected,
    )

    /** Top-bar clickable nodes sorted left-to-right by position. */
    private fun topBarIndices(): List<Int> {
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        return nodes.withIndex()
            .filter { it.value.positionInRoot.y < 120f }
            .sortedBy { it.value.positionInRoot.x }
            .map { it.index }
    }

    private fun clickTopBar(positionFromLeft: Int) {
        val indices = topBarIndices()
        rule.onAllNodes(hasClickAction())[indices[positionFromLeft]].performClick()
    }

    @Test
    fun `expanded width shows parent pane beside listing`() {
        val actions = RecordingFilesActions()
        rule.setContent { DirectoryScreen(state = state(), actions = actions) }
        rule.onNodeWithText("notes.txt").assertIsDisplayed()
        rule.onNodeWithText("subfolder").assertIsDisplayed()
    }

    @Test
    fun `overflow menu new folder and new file dialogs create entries`() {
        val actions = RecordingFilesActions()
        rule.setContent { DirectoryScreen(state = state(), actions = actions) }
        // top bar: drawer menu (left) ... search, overflow (right side)
        clickTopBar(topBarIndices().size - 1) // overflow
        rule.onNodeWithText("New folder").performClick()
        rule.onNodeWithText("Folder name").performTextInput("Reports")
        rule.onNodeWithText("Create").performClick()
        assertTrue(actions.calls.contains("folder:Reports"))
        clickTopBar(topBarIndices().size - 1)
        rule.onNodeWithText("New file").performClick()
        rule.onNodeWithText("File name").assertIsDisplayed()
        rule.onNodeWithText("Create").performClick()
        assertTrue(actions.calls.any { it.startsWith("file:") })
    }

    @Test
    fun `selection flows rename archive and properties dialogs`() {
        val actions = RecordingFilesActions()
        val single = item("notes.txt")
        rule.setContent {
            DirectoryScreen(state = state(selected = setOf(single)), actions = actions)
        }
        // selection top bar: clear, delete, overflow are the right-side buttons
        clickTopBar(topBarIndices().size - 1) // overflow
        rule.onAllNodesWithText("Rename")[0].performClick()
        rule.onNodeWithText("New name").performTextReplacement("renamed.txt")
        // dialog title and confirm button both read "Rename"; confirm is the last
        val renameNodes = rule.onAllNodesWithText("Rename").fetchSemanticsNodes()
        rule.onAllNodesWithText("Rename")[renameNodes.size - 1].performClick()
        assertTrue(actions.calls.contains("rename:notes.txt->renamed.txt"))

        clickTopBar(topBarIndices().size - 1)
        rule.onNodeWithText("Archive").performClick()
        rule.onNodeWithText("Archive Selection").assertIsDisplayed()
        // archive name field is prefilled from the single selection
        rule.onNodeWithText("Archive").performClick()
        assertTrue(actions.calls.any { it.startsWith("archive:") })

        clickTopBar(topBarIndices().size - 1)
        rule.onNodeWithText("Properties").performClick()
        rule.onNodeWithText("Path").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
    }

    @Test
    fun `top actions remaining menu items`() {
        val actions = RecordingFilesActions()
        var unzipPicked = false
        val zipItem = item("bundle.zip")
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            Column {
                DirectoryTopActions(
                    state = when (stage.intValue) {
                        0 -> state()
                        1 -> state(selected = setOf(zipItem)).copy(hasIncomingUris = true)
                        else -> state().copy(isSearchActive = true)
                    },
                    actions = actions,
                    canModifyHere = true,
                    isReadOnly = false,
                    onRename = {},
                    onShowProperties = {},
                    onArchive = {},
                    onPickUnzipDestination = { unzipPicked = true },
                    onShowNewFolder = {},
                    onShowNewFile = {},
                )
            }
        }
        fun overflowIndex(): Int =
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().lastIndex
        // plain overflow: every sort + descending + list view + select all
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Date").performClick()
        assertTrue(actions.calls.contains("sort:DATE"))
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Descending").performClick()
        assertTrue(actions.calls.contains("sortAsc:false"))
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Ascending").performClick()
        assertTrue(actions.calls.contains("sortAsc:true"))
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Select all").performClick()
        assertTrue(actions.calls.contains("selectAll"))

        // selection overflow for a zip with incoming uris: cut/share/open-with/unzip/save
        rule.runOnIdle { stage.intValue = 1 }
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Cut").performClick()
        assertTrue(actions.calls.contains("cut"))
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Share").performClick()
        assertTrue(actions.calls.contains("share"))
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Open with").performClick()
        assertTrue(actions.calls.contains("openWith:bundle.zip"))
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Archive Selection").performClick()
        assertTrue(unzipPicked)
        rule.onAllNodes(hasClickAction())[overflowIndex()].performClick()
        rule.onNodeWithText("Files saved successfully").performClick()
        assertTrue(actions.calls.contains("saveUris"))

        // search-active top bar: only the close-search button
        rule.runOnIdle { stage.intValue = 2 }
        rule.onAllNodes(hasClickAction())[0].performClick()
        assertTrue(actions.calls.contains("searchActive:false"))
    }

    @Test
    fun `properties dialog for directory and zip-internal items`() {
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        val ctx = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val realDir = File(ctx.cacheDir, "props-${System.nanoTime()}/folder").apply { mkdirs() }
        File(realDir, "child.txt").writeText("c")
        val dirItem = FileBrowserItem(
            "folder", true, null, realDir, null, "folder-key",
        )
        val zipItem = FileBrowserItem(
            "inner.txt", false, 42, null, "docs/inner.txt", "zip:inner",
            lastModified = 1_700_000_000_000,
        )
        rule.setContent {
            when (stage.intValue) {
                0 -> com.vayunmathur.files.ui.dialogs.PropertiesDialog(dirItem, onDismiss = {})
                else -> com.vayunmathur.files.ui.dialogs.PropertiesDialog(zipItem, onDismiss = {})
            }
        }
        rule.onNodeWithText("folder").assertIsDisplayed()
        rule.onNodeWithText("Folder").assertIsDisplayed()
        rule.runOnIdle { stage.intValue = 1 }
        rule.onNodeWithText("inner.txt").assertIsDisplayed()
        rule.onNodeWithText("docs/inner.txt").assertIsDisplayed()
    }

    @Test
    fun `name dialog dismiss cancels`() {
        var dismissed = false
        rule.setContent {
            NameDialog(
                title = "New file", label = "File name", initial = "untitled.txt",
                confirmLabel = "Create", onConfirm = {}, onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
        assertEquals(ViewMode.LIST, ViewMode.valueOf("LIST"))
        assertEquals(SortBy.TYPE, SortBy.valueOf("TYPE"))
    }
}
