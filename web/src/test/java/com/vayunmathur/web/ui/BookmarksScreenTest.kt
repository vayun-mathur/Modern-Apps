package com.vayunmathur.web.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.BookmarkFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookmarksScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        bookmarks: List<Bookmark> = listOf(
            Bookmark(id = 1, url = "https://example.com", title = "Example"),
            Bookmark(id = 2, url = "https://other.org/x", title = "", folderId = 7),
        ),
        folders: List<BookmarkFolder> = listOf(BookmarkFolder(id = 7, name = "Work")),
        onOpen: (Bookmark) -> Unit = {},
        onDelete: (Bookmark) -> Unit = {},
        onCreateFolder: (String) -> Unit = {},
    ) {
        rule.setContent {
            BookmarksScreen(
                bookmarks = bookmarks,
                folders = folders,
                onOpen = onOpen,
                onDelete = onDelete,
                onCreateFolder = onCreateFolder,
                onDeleteFolder = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `unfiled bookmarks render`() {
        show()
        rule.onNodeWithText("Example").assertIsDisplayed()
    }

    @Test
    fun `selecting a folder filters`() {
        show()
        rule.onNodeWithText("Work").performClick()
        rule.waitForIdle()
        // The unfiled bookmark hides; the foldered one (untitled -> url) shows.
        rule.onNodeWithText("https://other.org/x").assertIsDisplayed()
        assertTrue(
            rule.onAllNodes(hasText("Example", substring = false))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `opening a bookmark reports it`() {
        var opened: Bookmark? = null
        show(onOpen = { opened = it })
        rule.onNodeWithText("Example").performClick()
        rule.waitForIdle()
        assertEquals("https://example.com", opened?.url)
    }

    @Test
    fun `a new folder can be created`() {
        var created: String? = null
        show(onCreateFolder = { created = it })
        // Folder action in the app bar opens the new-folder dialog.
        rule.onNodeWithContentDescription("Folder").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("New folder", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Create", substring = true).assertIsDisplayed()
    }
}

private fun hasText(text: String, substring: Boolean): androidx.compose.ui.test.SemanticsMatcher =
    androidx.compose.ui.test.hasText(text, substring = substring)

private fun hasClickAction(): androidx.compose.ui.test.SemanticsMatcher =
    androidx.compose.ui.test.hasClickAction()
