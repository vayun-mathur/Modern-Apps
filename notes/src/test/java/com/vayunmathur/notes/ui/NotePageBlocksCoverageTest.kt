package com.vayunmathur.notes.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.FakeAndroidKeyStore
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.NoteBody
import com.vayunmathur.notes.data.withBody
import com.vayunmathur.notes.platform.NotesViewModel
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NotePageBlocksCoverageTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(app)
    private fun vm() = NotesViewModel(app, repo)

    @BeforeTest fun purge() {
        FakeAndroidKeyStore.install()
        runCatching { com.vayunmathur.library.util.DatabaseHelper(app).storePassphrase("test-passphrase") }
        runBlocking { repo.purge(); repo.deleteAllSnapshots() }
    }

    private fun idle() { rule.waitForIdle(); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }

    private fun seedBlockNote(): Long {
        val note = Note(title = "Block Note", content = "").withBody(
            NoteBody(
                listOf(
                    NoteBlock.Text("first block text", "b1"),
                    NoteBlock.Image("block-image.jpg", 0.5f, "img1"),
                    NoteBlock.Text("second block text", "b2"),
                ),
            ),
        )
        return runBlocking { repo.upsert(note.copy(syncId = "blocks1")) }
    }

    @Test fun notePageMoveAndDeleteBlockActions() {
        val id = seedBlockNote()
        val stack = NavBackStack<Route>(arrayOf(Route.NotesList, Route.Note(id)))
        val vm = vm()
        rule.setContent { NotePage(stack, vm, id) }
        rule.waitUntil(timeoutMillis = 8000) {
            runCatching { rule.onNodeWithText("Block Note").assertIsDisplayed(); true }.getOrDefault(false)
        }
        // Only image/ink blocks carry move/delete controls; they render inline.
        val downs = rule.onAllNodesWithContentDescription("Down").fetchSemanticsNodes()
        assertTrue(downs.isNotEmpty(), "no Down block controls rendered")
        rule.onAllNodesWithContentDescription("Down")[0].performClick()
        idle()
        assertEquals(Route.Note(id), stack.last())
        rule.onAllNodesWithContentDescription("Up")[0].performClick()
        idle()
        // Delete the image block; the editor stays open on the same note.
        val deletes = rule.onAllNodesWithContentDescription("Delete").fetchSemanticsNodes()
        assertTrue(deletes.size >= 2, "expected block + toolbar Delete buttons, got ${deletes.size}")
        rule.onAllNodesWithContentDescription("Delete")[0].performClick()
        idle()
        assertEquals(Route.Note(id), stack.last())
        // The image block's controls are gone once the block is deleted.
        assertEquals(0, rule.onAllNodesWithContentDescription("Down").fetchSemanticsNodes().size)
    }

    @Test fun notePageAddDrawingPushesInkEditorRoute() {
        val id = seedBlockNote()
        val stack = NavBackStack<Route>(arrayOf(Route.NotesList, Route.Note(id)))
        val vm = vm()
        rule.setContent { NotePage(stack, vm, id) }
        rule.waitUntil(timeoutMillis = 8000) {
            runCatching { rule.onNodeWithText("Block Note").assertIsDisplayed(); true }.getOrDefault(false)
        }
        rule.onNodeWithContentDescription("Add drawing").performClick()
        idle()
        // The ink editor is a route, not in-page state: the note stays under
        // a new InkEditor entry carrying this note's id and a fresh block id.
        val top = stack.last()
        assertTrue(top is Route.InkEditor, "expected InkEditor route, got $top")
        top as Route.InkEditor
        assertEquals(id, top.noteId)
        assertTrue(top.blockId.isNotBlank(), "new ink block id must not be blank")
        assertEquals(Route.Note(id), stack.backStack[stack.backStack.size - 2])
        // Popping the editor route (InkEditorPage onDone/onCancel) returns here.
        rule.runOnUiThread { stack.pop() }
        idle()
        assertEquals(Route.Note(id), stack.last())
    }

    @Test fun listPageTagRenameAndDeleteForwardToRepository() {
        runBlocking {
            repo.upsert(Note(title = "Home Note", content = "h", tags = listOf("Home"), syncId = "lp1"))
            repo.upsert(Note(title = "Idea Note", content = "i", tags = listOf("Ideas"), syncId = "lp2"))
        }
        val stack = NavBackStack<Route>(arrayOf(Route.NotesList))
        val vm = vm()
        rule.setContent { NotesListPage(stack, vm) }
        rule.waitUntil(timeoutMillis = 8000) {
            runCatching { rule.onNodeWithText("Home Note").assertIsDisplayed(); true }.getOrDefault(false)
        }
        // Rename Home -> work through the page's actions (ViewModel -> repository).
        rule.onNode(hasText("Home") and hasText("1")).performTouchInput { longClick() }
        idle()
        rule.onNodeWithText("Rename").performClick()
        idle()
        rule.onNodeWithText("Tag name").performTextReplacement("work")
        idle()
        rule.onNode(hasText("Rename") and hasClickAction()).performClick()
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline &&
            runBlocking { repo.getBySyncId("lp1") }?.tags != listOf("work")
        ) {
            Thread.sleep(30); idle()
        }
        assertEquals(listOf("work"), runBlocking { repo.getBySyncId("lp1") }?.tags)
        // Delete the Ideas tag through the same page path.
        rule.onNode(hasText("Ideas") and hasText("1")).performTouchInput { longClick() }
        idle()
        rule.onNodeWithText("Delete").performClick()
        idle()
        rule.onNode(hasText("Delete") and hasClickAction()).performClick()
        val deadline2 = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline2 &&
            runBlocking { repo.getBySyncId("lp2") }?.tags?.isNotEmpty() != false
        ) {
            Thread.sleep(30); idle()
        }
        assertEquals(emptyList(), runBlocking { repo.getBySyncId("lp2") }?.tags)
    }

    @Test fun notePageAddAndRemoveTagThroughEditor() {
        val id = seedBlockNote()
        val stack = NavBackStack<Route>(arrayOf(Route.NotesList, Route.Note(id)))
        val vm = vm()
        rule.setContent { NotePage(stack, vm, id) }
        rule.waitUntil(timeoutMillis = 8000) {
            runCatching { rule.onNodeWithText("Block Note").assertIsDisplayed(); true }.getOrDefault(false)
        }
        rule.onNode(
            androidx.compose.ui.test.hasSetTextAction() and hasText("Add tag"),
        ).performTextInput("  road   trip ")
        rule.onNode(
            androidx.compose.ui.test.hasSetTextAction() and hasText("road", substring = true),
        ).performImeAction()
        idle()
        rule.onNodeWithText("road trip").assertIsDisplayed()
        // Removing the chip removes the tag from the editor state.
        rule.onNodeWithContentDescription("Remove tag road trip").performClick()
        idle()
        assertEquals(
            0,
            rule.onAllNodesWithContentDescription("Remove tag road trip").fetchSemanticsNodes().size,
        )
        assertEquals(Route.Note(id), stack.last())
    }

    @Test fun editableNoteDestructuringReadsAndWrites() {
        val vm = vm()
        var readTitle: String? = null
        var write: ((Note) -> Unit)? = null
        rule.setContent {
            val (note, setNote) = vm.editableNote(0) { Note(0, "destructure-default", "") }
            readTitle = note.title
            write = setNote
        }
        idle()
        assertEquals("destructure-default", readTitle)
        rule.runOnUiThread { write!!.invoke(Note(0, "destructured-write", "body")) }
        rule.waitForIdle()
        assertEquals("destructured-write", readTitle)
    }
}
