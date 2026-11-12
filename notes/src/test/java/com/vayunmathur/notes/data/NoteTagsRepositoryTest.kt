package com.vayunmathur.notes.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tags through Room (#913): column round trip, and the repository's rename/merge and delete. */
@RunWith(RobolectricTestRunner::class)
class NoteTagsRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(context)

    @BeforeTest fun purge() = runTest {
        repo.purge(); repo.deleteAllSnapshots()
    }

    private suspend fun tagsOf(syncId: String) = repo.getBySyncId(syncId)!!.tags

    @Test fun tagsRoundTripThroughTheColumn() = runTest {
        repo.upsertRaw(Note(title = "t", content = "", syncId = "rt", tags = listOf("Work", "記 😀", "a \"quoted\" tag")))
        assertEquals(listOf("Work", "記 😀", "a \"quoted\" tag"), tagsOf("rt"))
        repo.upsertRaw(Note(title = "u", content = "", syncId = "untagged"))
        assertEquals(emptyList(), tagsOf("untagged"))
    }

    @Test fun converterStoresJsonAndToleratesGarbage() {
        val c = NoteConverters()
        assertEquals("[]", c.fromTags(emptyList()))
        assertEquals("""["a","b c"]""", c.fromTags(listOf("a", "b c")))
        assertEquals(listOf("a", "b c"), c.toTags("""["a","b c"]"""))
        assertEquals(emptyList(), c.toTags("not json"))
        assertEquals(emptyList(), c.toTags("""{"a":1}"""))
    }

    @Test fun renameAppliesToEveryNoteAndBumpsUpdatedAt() = runTest {
        repo.upsertRaw(Note(title = "1", content = "", syncId = "n1", updatedAt = 10, tags = listOf("work", "x")))
        repo.upsertRaw(Note(title = "2", content = "", syncId = "n2", updatedAt = 10, tags = listOf("Work")))
        repo.upsertRaw(Note(title = "3", content = "", syncId = "n3", updatedAt = 10, tags = listOf("home")))
        assertEquals(2, repo.renameTag("work", "office"))
        assertEquals(listOf("office", "x"), tagsOf("n1"))
        assertEquals(listOf("office"), tagsOf("n2"))
        assertEquals(listOf("home"), tagsOf("n3"))
        // Changed notes sync (local write bumps updatedAt); untouched ones do not.
        assertTrue(repo.getBySyncId("n1")!!.updatedAt > 10)
        assertTrue(repo.getBySyncId("n2")!!.updatedAt > 10)
        assertEquals(10L, repo.getBySyncId("n3")!!.updatedAt)
    }

    @Test fun renameIntoAnExistingTagMerges() = runTest {
        repo.upsertRaw(Note(title = "both", content = "", syncId = "b", tags = listOf("Home", "house")))
        repo.upsertRaw(Note(title = "old", content = "", syncId = "o", tags = listOf("house")))
        repo.renameTag("house", "home")
        assertEquals(listOf("Home"), tagsOf("b"))
        assertEquals(listOf("home"), tagsOf("o"))
    }

    @Test fun deleteStripsTheTagButKeepsEveryNote() = runTest {
        repo.upsertRaw(Note(title = "1", content = "", syncId = "d1", tags = listOf("trash", "keep")))
        repo.upsertRaw(Note(title = "2", content = "", syncId = "d2", tags = listOf("TRASH")))
        repo.upsertRaw(Note(title = "3", content = "", syncId = "d3"))
        assertEquals(2, repo.deleteTag("trash"))
        assertEquals(listOf("keep"), tagsOf("d1"))
        assertEquals(emptyList(), tagsOf("d2"))
        assertEquals(setOf("d1", "d2", "d3"), repo.getAll().map { it.syncId }.toSet())
        assertTrue(repo.getAll().none { it.deleted })
    }

    @Test fun tombstonesAreLeftAlone() = runTest {
        repo.upsertRaw(Note(title = "dead", content = "", syncId = "t", updatedAt = 5, deleted = true, tags = listOf("x")))
        assertEquals(0, repo.renameTag("x", "y"))
        assertEquals(0, repo.deleteTag("x"))
        assertEquals(listOf("x"), tagsOf("t"))
        assertEquals(5L, repo.getBySyncId("t")!!.updatedAt)
    }
}
