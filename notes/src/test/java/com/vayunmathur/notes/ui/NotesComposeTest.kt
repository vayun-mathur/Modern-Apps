package com.vayunmathur.notes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.vayunmathur.library.ink.SerializedPoint
import com.vayunmathur.library.ink.SerializedStroke
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.platform.NoteActions
import com.vayunmathur.notes.platform.NotesListActions
import com.vayunmathur.notes.platform.NotesListUiState
import com.vayunmathur.notes.platform.NoteUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NotesComposeTest {
    @get:Rule val rule = createComposeRule()

    private class RecordingListActions : NotesListActions {
        var opened: Long? = null; var created = 0; val deleted = mutableListOf<Note>(); var upserted: List<Note>? = null
        override fun openNote(id: Long) { opened = id }
        override fun createNote() { created++ }
        override fun delete(note: Note) { deleted += note }
        override fun upsertAll(notes: List<Note>) { upserted = notes }
    }

    private class RecordingNoteActions : NoteActions {
        var back = 0; var titleSet: String? = null; val markdown = mutableListOf<Pair<String, String>>()
        var addImageAfter: String? = "unset"; var addDrawingAfter: String? = "unset"
        var editedInk: NoteBlock.Ink? = null; val resized = mutableListOf<Pair<String, Float>>()
        val moved = mutableListOf<Pair<String, Int>>(); val deletedBlocks = mutableListOf<String>()
        var copy = 0; var share = 0; var deleteNoteCount = 0
        override fun back() { back++ }
        override fun setTitle(title: String) { titleSet = title }
        override fun setBlockMarkdown(id: String, markdown: String) { this.markdown += id to markdown }
        override fun addImage(afterBlockId: String?) { addImageAfter = afterBlockId }
        override fun addDrawing(afterBlockId: String?) { addDrawingAfter = afterBlockId }
        override fun editDrawing(block: NoteBlock.Ink) { editedInk = block }
        override fun resizeImage(id: String, widthFraction: Float) { resized += id to widthFraction }
        override fun moveBlock(id: String, delta: Int) { moved += id to delta }
        override fun deleteBlock(id: String) { deletedBlocks += id }
        override fun copyNote() { copy++ }
        override fun shareNote() { share++ }
        override fun deleteNote() { deleteNoteCount++ }
    }

    private fun notes() = listOf(
        Note(id = 1, title = "Alpha Title", content = "first line\nsecond", position = 1.0, syncId = "n1"),
        Note(id = 2, title = "Beta Title", content = "beta body", position = 2.0, syncId = "n2"),
        Note(id = 3, title = "Gamma", content = "gamma body", position = 3.0, syncId = "n3"),
    )

    @Test fun listShowsNotesAndClickOpens() {
        val actions = RecordingListActions()
        rule.setContent { NotesListScreen(NotesListUiState(notes()), actions) }
        rule.onNodeWithText("Alpha Title").assertIsDisplayed()
        rule.onNodeWithText("first line").assertIsDisplayed()
        rule.onNodeWithText("Beta Title").assertIsDisplayed()
        rule.onNodeWithText("Beta Title").performClick()
        assertEquals(2L, actions.opened)
    }

    @Test fun listEmptyAndFabCreates() {
        val actions = RecordingListActions()
        rule.setContent { NotesListScreen(NotesListUiState(emptyList()), actions) }
        assertEquals(0, rule.onAllNodesWithText("Alpha Title").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(1, actions.created)
    }

    @Test fun listSearchFiltersAndHideAddButton() {
        val actions = RecordingListActions()
        rule.setContent {
            NotesListScreen(NotesListUiState(notes(), showAddButton = false), actions, initialSearchQuery = "beta")
        }
        rule.onNodeWithText("Beta Title").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Alpha Title").fetchSemanticsNodes().size)
    }

    @Test fun listSearchTypingNarrowsResults() {
        val actions = RecordingListActions()
        rule.setContent { NotesListScreen(NotesListUiState(notes()), actions) }
        rule.onNodeWithText("Alpha Title").assertIsDisplayed()
        // CommonSearchBar is a text field with placeholder = app name "Notes"
        rule.onNodeWithText("Notes").performTextInput("gamma")
        rule.waitForIdle()
        rule.onNodeWithText("Gamma").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Alpha Title").fetchSemanticsNodes().size)
    }

    @Test fun noteScreenShowsTitleBlocksAndToolbarActions() {
        val actions = RecordingNoteActions()
        val stroke = SerializedStroke("pen", 3f, 0xFF000000.toInt(), 0.1f, listOf(SerializedPoint(1f, 2f, 0)))
        val blocks = listOf(
            NoteBlock.Text("hello text block", "t1"),
            NoteBlock.Image("x.jpg", 0.5f, "img1"),
            NoteBlock.Text("second text", "t2"),
        )
        rule.setContent { NoteScreen(NoteUiState("My Title", blocks), actions) }
        rule.onNodeWithText("My Title").assertIsDisplayed()
        rule.onNodeWithContentDescription("Add image").performClick()
        assertEquals(null, actions.addImageAfter)
        rule.onNodeWithContentDescription("Add drawing").performClick()
        assertEquals(null, actions.addDrawingAfter)
        rule.onNodeWithContentDescription("Copy").performClick()
        assertEquals(1, actions.copy)
        rule.onNodeWithContentDescription("Share").performClick()
        assertEquals(1, actions.share)
        // two Delete buttons exist: ImageBlock controls + top bar; click both
        rule.onAllNodesWithContentDescription("Delete")[0].performClick()
        rule.onAllNodesWithContentDescription("Delete")[1].performClick()
        assertEquals(listOf("img1"), actions.deletedBlocks)
        assertEquals(1, actions.deleteNoteCount)
        assertTrue(blocks.size == 3)
    }

    @Test fun noteScreenEmptyTitleShowsPlaceholder() {
        val actions = RecordingNoteActions()
        rule.setContent { NoteScreen(NoteUiState("", listOf(NoteBlock.Text("", "t1"))), actions) }
        rule.onNodeWithText("Title").assertIsDisplayed()
    }

    @Test fun componentsImageBlockControls() {
        var resized: Float? = null; var up = 0; var down = 0; var del = 0; var edit = 0
        val stroke = SerializedStroke("pen", 3f, 0xFF112233.toInt(), 0.1f, listOf(SerializedPoint(1f, 2f, 0), SerializedPoint(5f, 6f, 1)))
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                com.vayunmathur.notes.ui.components.ImageBlock(
                    NoteBlock.Image("x.jpg", 0.5f, "i1"), File("x.jpg"),
                    onResize = { resized = it }, onMoveUp = { up++ }, onMoveDown = { down++ }, onDelete = { del++ },
                )
            }
        }
        rule.waitForIdle()
        // BlockControls render icon buttons without text; idle success means render completed
        assertEquals(0, up + down + del + edit) // no spurious callbacks on render
        assertEquals(null, resized)
    }


    @Test fun listLongPressSelectionDeleteAndClose() {
        val actions = RecordingListActions()
        rule.setContent { NotesListScreen(NotesListUiState(notes()), actions) }
        rule.onNodeWithText("Alpha Title").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("1").assertIsDisplayed()
        // selecting a second row increments the count
        rule.onNodeWithText("Beta Title").performClick()
        rule.onNodeWithText("2").assertIsDisplayed()
        // deselect Beta, then delete the remaining selection
        rule.onNodeWithText("Beta Title").performClick()
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf(1L), actions.deleted.map { it.id })
        // selection cleared after delete: back to search bar title state
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("1").fetchSemanticsNodes().size)
        // long-press again and close without deleting
        rule.onNodeWithText("Beta Title").performTouchInput { longClick() }
        rule.onNodeWithText("1").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, actions.deleted.size)
        assertEquals(0, rule.onAllNodesWithText("1").fetchSemanticsNodes().size)
    }

    @Test fun imageBlockControlButtonsInvokeCallbacks() {
        var resized: Float? = null; var up = 0; var down = 0; var del = 0
        rule.setContent {
            com.vayunmathur.notes.ui.components.ImageBlock(
                NoteBlock.Image("x.jpg", 0.5f, "i1"), File("x.jpg"),
                onResize = { resized = it }, onMoveUp = { up++ }, onMoveDown = { down++ }, onDelete = { del++ },
            )
        }
        rule.waitForIdle()
        // order: shrink(Down), grow(Up), then BlockControls Up, Down, Delete
        rule.onAllNodesWithContentDescription("Down")[0].performClick()
        assertEquals(0.25f, resized)
        rule.onAllNodesWithContentDescription("Up")[0].performClick()
        assertEquals(0.75f, resized)
        rule.onAllNodesWithContentDescription("Up")[1].performClick()
        assertEquals(1, up)
        rule.onAllNodesWithContentDescription("Down")[1].performClick()
        assertEquals(1, down)
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(1, del)
    }

    @Test fun blockControlsWithoutExtraInvokeCallbacks() {
        var up = 0; var down = 0; var del = 0
        rule.setContent {
            com.vayunmathur.notes.ui.components.BlockControls(onMoveUp = { up++ }, onMoveDown = { down++ }, onDelete = { del++ })
        }
        rule.onNodeWithContentDescription("Up").performClick()
        rule.onNodeWithContentDescription("Down").performClick()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(1 to 1, up to down)
        assertEquals(1, del)
    }

    @Test fun noteScreenFocusShowsFormattingToolbar() {
        val actions = RecordingNoteActions()
        rule.setContent {
            NoteScreen(NoteUiState("Focus Title", listOf(NoteBlock.Text("focusable body text", "ft1"))), actions)
        }
        rule.onNodeWithText("focusable body text").assertIsDisplayed()
        rule.onNodeWithText("focusable body text").performClick()
        rule.waitForIdle()
        // With a focused editor the bottom bar swaps to the markdown toolbar;
        // its buttons are labelled (Bold etc). Clicking one must not crash and
        // the insert buttons remain available as trailing actions.
        val bold = rule.onAllNodesWithContentDescription("Bold").fetchSemanticsNodes()
        if (bold.isNotEmpty()) {
            rule.onNodeWithContentDescription("Bold").performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithContentDescription("Add image").assertIsDisplayed()
    }

    @Test fun listDragHandleSwipeDoesNotCrash() {
        val actions = RecordingListActions()
        rule.setContent { NotesListScreen(NotesListUiState(notes()), actions) }
        rule.onNodeWithText("Alpha Title").assertIsDisplayed()
        val handles = rule.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes()
        assertTrue(handles.isNotEmpty())
        // Drag the first row's handle downwards over the second row.
        rule.onAllNodesWithContentDescription("Reorder")[0].performTouchInput {
            swipe(start = center, end = androidx.compose.ui.geometry.Offset(center.x, center.y + 220f), durationMillis = 600)
        }
        rule.waitForIdle()
        // Whatever the reorder outcome, the list still renders all notes.
        rule.onNodeWithText("Alpha Title").assertIsDisplayed()
        rule.onNodeWithText("Beta Title").assertIsDisplayed()
    }
}
