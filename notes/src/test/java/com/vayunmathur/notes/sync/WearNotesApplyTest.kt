package com.vayunmathur.notes.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.NoteBody
import com.vayunmathur.notes.data.NotesRepository
import com.vayunmathur.notes.data.withBody
import com.vayunmathur.notes.domain.WearNote
import com.vayunmathur.notes.domain.WearNoteOp
import com.vayunmathur.notes.domain.WearOpKind
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phone-side application of watch ops: LWW, tombstones, rich-note refusal. */
@RunWith(RobolectricTestRunner::class)
class WearNotesApplyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo: NotesRepository get() = seedNotesDb(context)
    private val sync: WearNotesSync get() = WearNotesSync.get(context)

    @BeforeTest
    fun purge() = runTest { repo.purge() }

    private fun op(kind: WearOpKind, syncId: String, updatedAt: Long, title: String = "W", body: String = "watch body") =
        WearNoteOp(kind, WearNote(syncId = syncId, title = title, body = body, updatedAt = updatedAt))

    @Test
    fun watchCreateBecomesRealTextNote() = runTest {
        assertTrue(sync.applyWearOp(op(WearOpKind.UPSERT, "w1", 1_000L)))
        val note = repo.getBySyncId("w1")!!
        assertEquals("W", note.title)
        assertEquals("watch body", note.content)
        assertEquals(1_000L, note.updatedAt)
        assertFalse(note.deleted)
    }

    @Test
    fun staleEditLosesAndNewerEditWins() = runTest {
        repo.upsertRaw(Note(title = "Phone", content = "phone", syncId = "e1", updatedAt = 2_000L))
        assertFalse(sync.applyWearOp(op(WearOpKind.UPSERT, "e1", 1_500L, title = "Stale")))
        assertEquals("Phone", repo.getBySyncId("e1")!!.title)
        assertTrue(sync.applyWearOp(op(WearOpKind.UPSERT, "e1", 2_500L, title = "Watch newer")))
        val note = repo.getBySyncId("e1")!!
        assertEquals("Watch newer", note.title)
        assertEquals(2_500L, note.updatedAt)
    }

    @Test
    fun watchDeleteTombstones() = runTest {
        repo.upsertRaw(Note(title = "Phone", content = "phone", syncId = "d1", updatedAt = 2_000L))
        assertTrue(sync.applyWearOp(op(WearOpKind.DELETE, "d1", 3_000L)))
        assertTrue(repo.getBySyncId("d1")!!.deleted)
        assertTrue(repo.getAll().isEmpty())
        // Deleting a note the phone never had changes nothing.
        assertFalse(sync.applyWearOp(op(WearOpKind.DELETE, "missing", 3_000L)))
    }

    @Test
    fun watchEditRefusedForInkNoteButDeleteAllowed() = runTest {
        val ink = Note(title = "Sketch", content = "", syncId = "ink1", updatedAt = 2_000L)
            .withBody(NoteBody(listOf(NoteBlock.Ink())))
        repo.upsertRaw(ink)
        assertFalse(sync.applyWearOp(op(WearOpKind.UPSERT, "ink1", 3_000L, title = "Overwrite")))
        assertEquals("Sketch", repo.getBySyncId("ink1")!!.title)
        assertTrue(sync.applyWearOp(op(WearOpKind.DELETE, "ink1", 3_000L)))
        assertTrue(repo.getBySyncId("ink1")!!.deleted)
    }
}
