package com.vayunmathur.notes.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class NoteDaoTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db: NoteDatabase = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao get() = db.noteDao()
    private val snapDao get() = db.syncSnapshotDao()

    @AfterTest fun close() = db.close()

    @Test fun crudFlowsAndTombstoneVisibility() = runTest {
        val idB = dao.upsertRaw(Note(title = "B", content = "b", syncId = "sync-b", position = 2.0, updatedAt = 200))
        val idA = dao.upsertRaw(Note(title = "A", content = "a", syncId = "sync-a", position = 1.0, updatedAt = 100))
        assertTrue(idA > 0 && idB > 0)
        assertEquals(setOf("sync-a", "sync-b"), dao.getAll().map { it.syncId }.toSet())
        assertEquals(setOf("sync-a", "sync-b"), dao.getAllFlow().first().map { it.syncId }.toSet())
        assertEquals("A", dao.getByIdFlow(idA).first()?.title)
        assertEquals("B", dao.getBySyncId("sync-b")?.title)
        assertNull(dao.getBySyncId("missing"))
        assertNull(dao.getByIdFlow(9999).first())
        // upsert (local) bumps updatedAt
        val before = dao.getBySyncId("sync-a")!!.updatedAt
        dao.upsert(dao.getBySyncId("sync-a")!!.copy(title = "A2"))
        val after = dao.getBySyncId("sync-a")!!
        assertEquals("A2", after.title)
        assertTrue(after.updatedAt >= before)
        // deleted rows hidden from getAll / getByIdFlow but present in getAllIncludingDeleted & getBySyncId
        dao.upsertRaw(after.copy(deleted = true))
        assertEquals(listOf("sync-b"), dao.getAll().map { it.syncId })
        assertNull(dao.getByIdFlow(idA).first())
        assertTrue(dao.getBySyncId("sync-a")!!.deleted)
        assertEquals(2, dao.getAllIncludingDeleted().size)
    }

    @Test fun updatedSinceUpsertAllAndDeletes() = runTest {
        dao.upsertAllRaw(listOf(
            Note(title = "X", content = "", syncId = "x", updatedAt = 5000),
            Note(title = "Y", content = "", syncId = "y", updatedAt = 1000),
            Note(title = "Z", content = "", syncId = "z", updatedAt = 9000, deleted = true),
        ))
        assertEquals(setOf("x", "z"), dao.getUpdatedSince(4000).map { it.syncId }.toSet())
        assertEquals(3, dao.getUpdatedSince(0).size)
        dao.upsertAll(listOf(Note(title = "W", content = "", syncId = "w", updatedAt = 10)))
        assertEquals(4, dao.getAllIncludingDeleted().size)
        assertEquals(1, dao.deleteBySyncId("w"))
        assertEquals(0, dao.deleteBySyncId("w"))
        val y = dao.getBySyncId("y")!!
        assertEquals(1, dao.delete(y))
        assertNull(dao.getBySyncId("y"))
        dao.deleteAll()
        assertTrue(dao.getAllIncludingDeleted().isEmpty())
    }

    @Test fun snapshotDaoCrud() = runTest {
        snapDao.upsert(SyncSnapshot("s1", SyncSnapshot.KIND_NOTE, "h1", 1, 2, 3))
        snapDao.upsert(SyncSnapshot("s2", "other", "h2", 4, 5, 6))
        snapDao.upsert(SyncSnapshot("s1", SyncSnapshot.KIND_NOTE, "h1b", 7, 8, 9))
        val all = snapDao.getAll()
        assertEquals(2, all.size)
        assertEquals("h1b", all.first { it.syncId == "s1" }.contentHash)
        assertEquals("note", SyncSnapshot.KIND_NOTE)
        snapDao.deleteBySyncId("s1")
        assertEquals(listOf("s2"), snapDao.getAll().map { it.syncId })
        snapDao.deleteAll()
        assertTrue(snapDao.getAll().isEmpty())
    }

    @Test fun dbNameAndConfigsShape() {
        assertEquals("notes-db", DB_NAME)
        assertNotNull(NoteDatabase.migrations)
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4), NoteDatabase.migrations.map { it.startVersion to it.endVersion })
    }
}

class NoteMigrationTest {
    private fun openV1(): SQLiteConnection {
        val conn = BundledSQLiteDriver().open(":memory:")
        conn.execSQL("CREATE TABLE `Note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `position` REAL NOT NULL)")
        conn.execSQL("INSERT INTO Note (title, content, position) VALUES ('old', 'body', 3.0)")
        return conn
    }
    private fun columns(conn: SQLiteConnection, table: String): Set<String> {
        val out = mutableSetOf<String>()
        conn.prepare("PRAGMA table_info(`$table`)").use { while (it.step()) out += it.getText(1) }
        return out
    }

    @Test fun migrate1to2AddsBlocks() {
        val conn = openV1()
        kotlinx.coroutines.runBlocking { NoteDatabase.migrations.single { it.startVersion == 1 }.migrate(conn) }
        assertTrue("blocks" in columns(conn, "Note"))
        conn.prepare("SELECT title, blocks FROM Note").use { assertTrue(it.step()); assertEquals("old", it.getText(0)); assertTrue(it.isNull(1)) }
        conn.close()
    }

    @Test fun migrate2to3AddsSyncColumnsAndSnapshotTable() {
        val conn = openV1()
        kotlinx.coroutines.runBlocking {
            NoteDatabase.migrations.single { it.startVersion == 1 }.migrate(conn)
            NoteDatabase.migrations.single { it.startVersion == 2 }.migrate(conn)
        }
        assertTrue(columns(conn, "Note").containsAll(setOf("syncId", "updatedAt", "deleted")))
        assertEquals(setOf("syncId", "kind", "contentHash", "localUpdatedAt", "remoteModified", "lastSyncedAt"), columns(conn, "SyncSnapshot"))
        conn.prepare("SELECT syncId, updatedAt FROM Note").use {
            assertTrue(it.step())
            assertEquals(32, it.getText(0).length)
            assertTrue(it.getLong(1) > 0)
        }
        conn.close()
    }
}
