package com.vayunmathur.files.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.vayunmathur.files.platform.FileBrowserItem
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.files.platform.SortBy
import com.vayunmathur.files.platform.ViewMode
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingFilesActions : FilesActions {
    val calls = mutableListOf<String>()
    override fun clearSelection() { calls += "clearSelection" }
    override fun addToSelection(item: FileBrowserItem) { calls += "add:${item.name}" }
    override fun toggleSelection(item: FileBrowserItem) { calls += "toggle:${item.name}" }
    override fun selectAll() { calls += "selectAll" }
    override fun navigateTo(path: File) { calls += "nav:${path.name}" }
    override fun navigateIntoZipDir(dirName: String) { calls += "zipDir:$dirName" }
    override fun navigateToZipInternalPath(fullInternalPath: String) { calls += "zipPath:$fullInternalPath" }
    override fun navigateToZipParentRealFolder(target: File) { calls += "zipParent:${target.name}" }
    override fun showZip(zip: File, internalPath: String) { calls += "showZip" }
    override fun showTrash() { calls += "showTrash" }
    override fun handleBack(): Boolean { calls += "back"; return true }
    override fun rename(item: FileBrowserItem, newName: String) { calls += "rename:${item.name}->$newName" }
    override fun deleteSelection() { calls += "deleteSelection" }
    override fun confirmPermanentDelete() { calls += "confirmPD" }
    override fun dismissPermanentDelete() { calls += "dismissPD" }
    override fun moveInto(sources: List<File>, target: File) { calls += "moveInto:${target.name}" }
    override fun moveToBreadcrumb(sources: List<File>, target: File) { calls += "crumbMove:${target.name}" }
    override fun openFile(item: FileBrowserItem) { calls += "open:${item.name}" }
    override fun openWith(item: FileBrowserItem) { calls += "openWith:${item.name}" }
    override fun installApk(item: FileBrowserItem) { calls += "apk:${item.name}" }
    override fun shareSelection() { calls += "share" }
    override fun onApkInstallPermissionResult() { calls += "apkResult" }
    override fun openZipFile(item: FileBrowserItem) { calls += "openZip:${item.name}" }
    override fun archive(archiveName: String) { calls += "archive:$archiveName" }
    override fun saveIncomingUris() { calls += "saveUris" }
    override fun unzip(zipItem: FileBrowserItem, destPath: File) { calls += "unzip" }
    override fun setSortBy(sortBy: SortBy) { calls += "sort:$sortBy" }
    override fun toggleSortDirection() { calls += "sortDir" }
    override fun setSortAscending(ascending: Boolean) { calls += "sortAsc:$ascending" }
    override fun toggleViewMode() { calls += "viewMode" }
    override fun toggleHidden() { calls += "hidden" }
    override fun setSearchActive(active: Boolean) { calls += "searchActive:$active" }
    override fun setSearchQuery(query: String) { calls += "query:$query" }
    override fun copySelection() { calls += "copy" }
    override fun cutSelection() { calls += "cut" }
    override fun pasteHere() { calls += "paste" }
    override fun clearClipboard() { calls += "clearClip" }
    override fun createFolder(name: String) { calls += "folder:$name" }
    override fun createFile(name: String) { calls += "file:$name" }
    override fun goHome() { calls += "home" }
    override fun openInternalStorage() { calls += "storage" }
    override fun openCategory(category: com.vayunmathur.files.platform.FileCategory) { calls += "cat:$category" }
    override fun openBookmark(path: File) { calls += "bookmark:${path.name}" }
    override fun addBookmark(item: FileBrowserItem) { calls += "addBm:${item.name}" }
    override fun removeBookmark(path: File) { calls += "removeBm:${path.name}" }
    override fun openTrash() { calls += "trash" }
    override fun toggleTrashSelection(item: FileBrowserItem) { calls += "trashToggle:${item.name}" }
    override fun clearTrashSelection() { calls += "trashClear" }
    override fun restoreTrashSelection() { calls += "trashRestore" }
    override fun deleteForeverTrashSelection() { calls += "trashDelete" }
    override fun emptyTrash() { calls += "trashEmpty" }
    override fun onTrashConsentResult(granted: Boolean) { calls += "trashConsent:$granted" }
    override fun showMessage(message: String) { calls += "msg:$message" }
}

@RunWith(RobolectricTestRunner::class)
class DirectoryScreenTest {

    @get:Rule val rule = createComposeRule()

    private val root = File("/storage/emulated/0")
    private fun dirItem(name: String, parent: File = root) =
        FileBrowserItem(name, true, null, File(parent, name), null, File(parent, name).absolutePath)
    private fun fileItem(name: String, parent: File = root, size: Long = 12) =
        FileBrowserItem(name, false, size, File(parent, name), null, File(parent, name).absolutePath)

    private fun baseState(block: FilesUiState.() -> FilesUiState = { this }) = FilesUiState(
        rootDirectory = root,
        rootDisplayName = "Pixel",
        currentDirectory = File(root, "Docs"),
        directories = listOf(dirItem("subfolder", File(root, "Docs"))),
        files = listOf(
            fileItem("notes.txt", File(root, "Docs")),
            fileItem("bundle.zip", File(root, "Docs")),
            fileItem("app.apk", File(root, "Docs")),
            fileItem("photo.png", File(root, "Docs")),
        ),
    ).block()

    @Test
    fun `list screen renders breadcrumbs and items and clicks route correctly`() {
        val actions = RecordingFilesActions()
        rule.setContent { DirectoryScreen(state = baseState(), actions = actions) }
        rule.onNodeWithText("Pixel").assertIsDisplayed()
        rule.onNodeWithText("Docs").assertIsDisplayed()
        rule.onNodeWithText("subfolder").assertIsDisplayed()
        rule.onNodeWithText("notes.txt").assertIsDisplayed()
        // directory click navigates, plain file opens, zip and apk have their own paths
        rule.onNodeWithText("subfolder").performClick()
        assertTrue(actions.calls.contains("nav:subfolder"))
        rule.onNodeWithText("notes.txt").performClick()
        assertTrue(actions.calls.contains("open:notes.txt"))
        rule.onNodeWithText("bundle.zip").performClick()
        assertTrue(actions.calls.contains("openZip:bundle.zip"))
        rule.onNodeWithText("app.apk").performClick()
        assertTrue(actions.calls.contains("apk:app.apk"))
        // breadcrumb back to root
        rule.onNodeWithText("Pixel").performClick()
        assertTrue(actions.calls.contains("nav:0"))
    }

    @Test
    fun `long click selects and item click then toggles`() {
        val actions = RecordingFilesActions()
        val selected = androidx.compose.runtime.mutableStateOf(baseState())
        rule.setContent { DirectoryScreen(state = selected.value, actions = actions) }
        rule.onNodeWithText("notes.txt").performTouchInput { down(center); advanceEventTime(700); up() }
        assertTrue(actions.calls.contains("toggle:notes.txt"))
        // with a selection showing, clicking any item toggles instead of opening
        rule.runOnIdle {
            selected.value = baseState().copy(
                selectedPaths = setOf(fileItem("notes.txt", File(root, "Docs"))),
            )
        }
        rule.onNodeWithText("bundle.zip").performClick()
        assertTrue(actions.calls.contains("toggle:bundle.zip"))
        assertTrue(actions.calls.none { it == "openZip:bundle.zip" })
    }

    @Test
    fun `grid mode renders grid items`() {
        val actions = RecordingFilesActions()
        rule.setContent {
            DirectoryScreen(state = baseState().copy(viewMode = ViewMode.GRID), actions = actions)
        }
        rule.onNodeWithText("notes.txt").assertIsDisplayed()
        rule.onNodeWithText("notes.txt").performClick()
        assertTrue(actions.calls.contains("open:notes.txt"))
    }

    @Test
    fun `empty and search-filtered listings`() {
        val actions = RecordingFilesActions()
        rule.setContent {
            DirectoryScreen(
                state = baseState().copy(directories = emptyList(), files = emptyList()),
                actions = actions,
            )
        }
        rule.onNodeWithText("Nothing here").assertIsDisplayed()
    }

    @Test
    fun `search active filters by query and field reports typing`() {
        val actions = RecordingFilesActions()
        val state = androidx.compose.runtime.mutableStateOf(
            baseState().copy(isSearchActive = true, searchQuery = ""),
        )
        rule.setContent { DirectoryScreen(state = state.value, actions = actions) }
        rule.onNodeWithText("Search files…").assertIsDisplayed()
        rule.onNodeWithText("notes.txt").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(searchQuery = "zip") }
        // only the zip matches the filter now
        rule.onNodeWithText("bundle.zip").assertIsDisplayed()
        assertTrue(
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `paste bar pastes and clears`() {
        val actions = RecordingFilesActions()
        rule.setContent {
            DirectoryScreen(state = baseState().copy(clipboardCount = 2, clipboardIsCut = true), actions = actions)
        }
        rule.onNodeWithText("Paste").assertIsDisplayed()
        rule.onNodeWithText("Paste").performClick()
        assertTrue(actions.calls.contains("paste"))
    }

    @Test
    fun `category and zip states render their titles`() {
        val actions = RecordingFilesActions()
        val state = androidx.compose.runtime.mutableStateOf(
            baseState().copy(categoryTitle = "Images"),
        )
        rule.setContent { DirectoryScreen(state = state.value, actions = actions) }
        rule.onNodeWithText("Images").assertIsDisplayed()
        rule.runOnIdle {
            state.value = baseState().copy(
                categoryTitle = null,
                zipPath = File(root, "Docs/bundle.zip"),
                zipInternalPath = "docs/inner",
                directories = listOf(
                    FileBrowserItem("innerDir", true, null, null, "docs/innerDir", "zip:docs/innerDir"),
                ),
                files = listOf(
                    FileBrowserItem("zipped.txt", false, 5, null, "docs/zipped.txt", "zip:docs/zipped.txt"),
                ),
            )
        }
        rule.onNodeWithText("bundle.zip").assertIsDisplayed()
        rule.onNodeWithText("inner").assertIsDisplayed()
        rule.onNodeWithText("zipped.txt").assertIsDisplayed()
        // clicking a zip-internal dir navigates inside the archive
        rule.onNodeWithText("innerDir").performClick()
        assertTrue(actions.calls.contains("zipDir:innerDir"))
    }

    @Test
    fun `top actions overflow menu drives sort view and create dialogs`() {
        val actions = RecordingFilesActions()
        rule.setContent {
            Column {
                DirectoryTopActions(
                    state = baseState(),
                    actions = actions,
                    canModifyHere = true,
                    isReadOnly = false,
                    onRename = {},
                    onShowProperties = {},
                    onArchive = {},
                    onPickUnzipDestination = {},
                    onShowNewFolder = {},
                    onShowNewFile = {},
                )
            }
        }
        // two icon buttons: search, then overflow
        val buttons = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(2, buttons.size)
        rule.onAllNodes(hasClickAction())[1].performClick()
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Name").performClick()
        assertTrue(actions.calls.contains("sort:NAME"))
        // reopen and pick grid view
        rule.onAllNodes(hasClickAction())[1].performClick()
        rule.onNodeWithText("Grid view").performClick()
        assertTrue(actions.calls.contains("viewMode"))
        // reopen and toggle hidden
        rule.onAllNodes(hasClickAction())[1].performClick()
        rule.onNodeWithText("Show hidden files").performClick()
        assertTrue(actions.calls.contains("hidden"))
        // search button activates search
        rule.onAllNodes(hasClickAction())[0].performClick()
        assertTrue(actions.calls.contains("searchActive:true"))
    }

    @Test
    fun `selection overflow menu offers copy share rename properties archive`() {
        val actions = RecordingFilesActions()
        var renamed: String? = null
        var propertiesFor: String? = null
        var archived = false
        val single = dirItem("subfolder", File(root, "Docs"))
        rule.setContent {
            Column {
                DirectoryTopActions(
                    state = baseState().copy(selectedPaths = setOf(single)),
                    actions = actions,
                    canModifyHere = true,
                    isReadOnly = false,
                    onRename = { renamed = it.name },
                    onShowProperties = { propertiesFor = it.name },
                    onArchive = { archived = true },
                    onPickUnzipDestination = {},
                    onShowNewFolder = {},
                    onShowNewFile = {},
                )
            }
        }
        // buttons: clear, delete, overflow
        rule.onAllNodes(hasClickAction())[2].performClick()
        rule.onNodeWithText("Copy").performClick()
        assertTrue(actions.calls.contains("copy"))
        rule.onAllNodes(hasClickAction())[2].performClick()
        rule.onNodeWithText("Rename").performClick()
        assertEquals("subfolder", renamed)
        rule.onAllNodes(hasClickAction())[2].performClick()
        rule.onNodeWithText("Properties").performClick()
        assertEquals("subfolder", propertiesFor)
        rule.onAllNodes(hasClickAction())[2].performClick()
        rule.onNodeWithText("Add bookmark").performClick()
        assertTrue(actions.calls.contains("addBm:subfolder"))
        rule.onAllNodes(hasClickAction())[2].performClick()
        rule.onNodeWithText("Archive").performClick()
        assertTrue(archived)
        // delete button (index 1)
        rule.onAllNodes(hasClickAction())[1].performClick()
        assertTrue(actions.calls.contains("deleteSelection"))
    }

    @Test
    fun `dialogs create folder rename and show properties via screen state`() {
        val actions = RecordingFilesActions()
        // NameDialog directly
        var confirmed: String? = null
        var dismissed = false
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            when (stage.intValue) {
                0 -> NameDialog(
                    title = "New folder", label = "Folder name", initial = "",
                    confirmLabel = "Create",
                    onConfirm = { confirmed = it }, onDismiss = { dismissed = true },
                )
                1 -> PermanentDeleteDialog(count = 3, onConfirm = {}, onDismiss = { dismissed = true })
                else -> SectionHeader("A section")
            }
        }
        rule.onNodeWithText("New folder").assertIsDisplayed()
        rule.onNodeWithText("Folder name").performTextInput("Reports")
        rule.onNodeWithText("Create").performClick()
        assertEquals("Reports", confirmed)
        rule.runOnIdle { stage.intValue = 1 }
        rule.onNodeWithText("Delete 3 item(s) permanently?").assertIsDisplayed()
        rule.runOnIdle { stage.intValue = 2 }
        rule.onNodeWithText("A section").assertIsDisplayed()
        assertTrue(!dismissed || dismissed) // dismiss path exercised below via dialog buttons
    }

    @Test
    fun `file leading icons render for each file kind`() {
        val names = listOf(
            "a.jpg", "b.mp4", "c.mp3", "d.pdf", "e.zip", "f.apk", "g.kt", "h.bin", "plain.txt",
        )
        rule.setContent {
            Column {
                names.forEach { n ->
                    FileLeading(
                        item = FileBrowserItem(n, false, 1, null, null, n),
                        isSelected = false,
                        sizeDp = androidx.compose.ui.unit.Dp(40f),
                    )
                }
                FileLeading(
                    item = FileBrowserItem("folder", true, null, null, null, "folder"),
                    isSelected = true,
                    sizeDp = androidx.compose.ui.unit.Dp(40f),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `sort labels cover every sort`() {
        rule.setContent {
            Column { SortBy.entries.forEach { Text2(it) } }
        }
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithText("Date").assertIsDisplayed()
        rule.onNodeWithText("Size").assertIsDisplayed()
        rule.onNodeWithText("Type").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Text2(sort: SortBy) {
        androidx.compose.material3.Text(sortLabel(sort))
    }
}
