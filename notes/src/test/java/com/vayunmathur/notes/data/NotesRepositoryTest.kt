package com.vayunmathur.notes.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.notes.notesDb
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NotesRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(context)

    @BeforeTest fun purge() = runTest {
        repo.purge(); repo.deleteAllSnapshots()
    }

    @Test fun singletonIsStable() {
        assertSame(repo, NotesRepository.get(context))
        assertSame(repo, seedNotesDb(context))
    }

    @Test fun upsertGetAndFlow() = runTest {
        val id = repo.upsert(Note(title = "First", content = "hello", syncId = "r1"))
        assertTrue(id > 0)
        repo.upsert(Note(title = "Second", content = "world", syncId = "r2"))
        assertEquals(setOf("r1", "r2"), repo.getAll().map { it.syncId }.toSet())
        assertEquals(setOf("r1", "r2"), repo.notes.first().map { it.syncId }.toSet())
        assertEquals("First", repo.noteByIdFlow(id).first()?.title)
        assertNull(repo.noteByIdFlow(424242).first())
        assertEquals("Second", repo.getBySyncId("r2")?.title)
        // raw preserves timestamp, local upsert bumps it
        repo.upsertRaw(Note(title = "Raw", content = "", syncId = "raw", updatedAt = 1234))
        assertEquals(1234L, repo.getBySyncId("raw")!!.updatedAt)
        repo.upsert(repo.getBySyncId("raw")!!.copy(title = "Raw2"))
        assertTrue(repo.getBySyncId("raw")!!.updatedAt > 1234L)
        assertEquals("Raw2", repo.getBySyncId("raw")!!.title)
    }

    @Test fun deleteTombstonesAndPurge() = runTest {
        repo.upsert(Note(title = "A", content = "", syncId = "del-a"))
        val a = repo.getBySyncId("del-a")!!
        assertEquals(1, repo.delete(a))
        assertTrue(repo.getAll().isEmpty())
        assertTrue(repo.getBySyncId("del-a")!!.deleted)
        assertEquals(1, repo.tombstoneBySyncId("del-a")) // already tombstoned still 1
        assertEquals(0, repo.tombstoneBySyncId("missing"))
        repo.upsert(Note(title = "B", content = "", syncId = "del-b"))
        assertEquals(1, repo.tombstoneBySyncId("del-b"))
        assertTrue(repo.getBySyncId("del-b")!!.deleted)
        assertEquals(1, repo.deleteBySyncId("del-b"))
        assertNull(repo.getBySyncId("del-b"))
        repo.purge()
        assertNull(repo.getBySyncId("del-a"))
    }

    @Test fun purgeExpiredTombstonesKeepsRecentAndLivePurgesOld() = runTest {
        val now = 1_800_000_000_000L
        val day = 24L * 60 * 60 * 1000
        // Live rows are never purged, however old.
        repo.upsertRaw(Note(title = "live-old", content = "", syncId = "gc-live", updatedAt = now - 200 * day))
        // Recent tombstone (inside the horizon) and one exactly at the
        // cutoff are kept: only strictly-older tombstones are purged.
        repo.upsertRaw(Note(title = "recent", content = "", syncId = "gc-recent", updatedAt = now - 10 * day, deleted = true))
        repo.upsertRaw(
            Note(
                title = "at-cutoff", content = "", syncId = "gc-cutoff",
                updatedAt = NotesRepository.tombstoneCutoff(now), deleted = true,
            ),
        )
        repo.upsertRaw(Note(title = "old", content = "", syncId = "gc-old", updatedAt = now - 91 * day, deleted = true))
        repo.upsertSnapshot(SyncSnapshot("gc-old", SyncSnapshot.KIND_NOTE, "h-old", 1, 2, 3))
        repo.upsertSnapshot(SyncSnapshot("gc-recent", SyncSnapshot.KIND_NOTE, "h-recent", 1, 2, 3))

        assertEquals(1, repo.purgeExpiredTombstones(now))

        assertEquals("live-old", repo.getBySyncId("gc-live")?.title)
        assertTrue(repo.getBySyncId("gc-recent")!!.deleted)
        assertTrue(repo.getBySyncId("gc-cutoff")!!.deleted)
        assertNull(repo.getBySyncId("gc-old"))
        // The expired tombstone's baseline snapshot goes with it; the
        // recent tombstone's snapshot is kept.
        assertEquals(listOf("gc-recent"), repo.getAllSnapshots().map { it.syncId })
        // A second run at the same instant is a no-op.
        assertEquals(0, repo.purgeExpiredTombstones(now))
    }

    @Test fun tombstoneRetentionIsNinetyDays() {
        assertEquals(90L * 24 * 60 * 60 * 1000, NotesRepository.TOMBSTONE_RETENTION_MS)
        assertEquals(1_000L - NotesRepository.TOMBSTONE_RETENTION_MS, NotesRepository.tombstoneCutoff(1_000L))
    }

    @Test fun updatedSinceUpsertAllAndSnapshots() = runTest {
        repo.upsertAllRaw(listOf(
            Note(title = "old", content = "", syncId = "u1", updatedAt = 10),
            Note(title = "new", content = "", syncId = "u2", updatedAt = 9999),
        ))
        assertEquals(listOf("u2"), repo.getUpdatedSince(100).map { it.syncId })
        repo.upsertAll(listOf(Note(title = "batch", content = "", syncId = "u3")))
        assertEquals(3, repo.getAll().size)
        repo.upsertSnapshot(SyncSnapshot("u1", SyncSnapshot.KIND_NOTE, "hash", 1, 2, 3))
        repo.upsertSnapshot(SyncSnapshot("u2", SyncSnapshot.KIND_NOTE, "hash2", 4, 5, 6))
        assertEquals(setOf("u1", "u2"), repo.getAllSnapshots().map { it.syncId }.toSet())
        repo.deleteSnapshotBySyncId("u1")
        assertEquals(listOf("u2"), repo.getAllSnapshots().map { it.syncId })
        repo.deleteAllSnapshots()
        assertTrue(repo.getAllSnapshots().isEmpty())
    }

    @Test fun upsertAllBumpsUpdatedAtSoReorderSyncs() = runTest {
        repo.upsertRaw(Note(title = "A", content = "", syncId = "ord-a", updatedAt = 10, position = 1.0))
        val stored = repo.getBySyncId("ord-a")!!
        repo.upsertAll(listOf(stored.copy(position = 99.0)))
        val after = repo.getBySyncId("ord-a")!!
        assertEquals(99.0, after.position)
        assertTrue(after.updatedAt > 10, "reorder must bump updatedAt or sync never sees it: ${after.updatedAt}")
        assertEquals(listOf("ord-a"), repo.getUpdatedSince(100).map { it.syncId })
    }

    @Test fun migrateImageReferencesRewritesOnlyImageBlocks() = runTest {
        val body = NoteBody(listOf(
            NoteBlock.Text("caption", "t1"),
            NoteBlock.Image("img_old.jpg", 0.5f, "i1"),
            NoteBlock.Image("keep.jpg", 1f, "i2"),
        ))
        val note = Note(title = "pic", content = "caption").withBody(body)
        repo.upsertRaw(note.copy(syncId = "img-note", updatedAt = 777))
        repo.upsertRaw(Note(title = "plain", content = "x", syncId = "plain", updatedAt = 777))
        repo.migrateImageReferences(emptyMap()) // no-op
        assertEquals("img_old.jpg", (repo.getBySyncId("img-note")!!.body().blocks[1] as NoteBlock.Image).fileName)
        repo.migrateImageReferences(mapOf("img_old.jpg" to "abc123.jpg", "unused.jpg" to "zzz.jpg"))
        val migrated = repo.getBySyncId("img-note")!!
        val blocks = migrated.body().blocks
        assertEquals("abc123.jpg", (blocks[1] as NoteBlock.Image).fileName)
        assertEquals("keep.jpg", (blocks[2] as NoteBlock.Image).fileName)
        assertEquals(777L, migrated.updatedAt) // raw rewrite must not bump
        assertEquals("plain", repo.getBySyncId("plain")!!.title)
    }

    @Test fun readAndClearLegacyWithoutLegacyDbIsEmpty() = runTest {
        assertTrue(repo.readAndClearLegacyNotes().isEmpty())
    }

    @Test fun corruptBodyFallsBackToContent() {
        val note = Note(title = "t", content = "legacy text", blocks = "{not json")
        val body = note.body()
        assertEquals(1, body.blocks.size)
        assertEquals("legacy text", (body.blocks.single() as NoteBlock.Text).markdown)
        // withBody syncs content trimming each text block
        val updated = note.withBody(NoteBody(listOf(NoteBlock.Text("  hi  ", "a"), NoteBlock.Image("x.jpg", id = "b"), NoteBlock.Text(" there ", "c"))))
        assertEquals("hi\n\nthere", updated.content)
        assertEquals(3, updated.body().blocks.size)
    }
}
