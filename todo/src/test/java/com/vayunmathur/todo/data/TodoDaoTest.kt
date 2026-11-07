package com.vayunmathur.todo.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TodoDaoTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db: TodoDatabase = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).allowMainThreadQueries().build()
    private val dao get() = db.todoDao()
    private val listDao get() = db.todoListDao()
    private val snapDao get() = db.syncSnapshotDao()

    @AfterTest fun close() = db.close()

    @Test fun todoCrudAndOrdering() = runTest {
        dao.upsert(Todo(syncId = "b", title = "B", position = 2.0))
        dao.upsert(Todo(syncId = "a", title = "A", position = 1.0))
        assertEquals(listOf("a", "b"), dao.getAll().map { it.syncId })
        assertEquals(listOf("a", "b"), dao.getAllFlow().first().map { it.syncId })
        assertEquals("A", dao.getBySyncId("a")?.title)
        assertEquals("A", dao.getBySyncIdFlow("a").first()?.title)
        assertNotNull(dao.getAll().first().updatedAt)
        val aId = dao.getBySyncId("a")!!.id
        dao.upsert(Todo(id = aId, syncId = "a", title = "A2", position = 1.0, deleted = true))
        assertEquals(listOf("b"), dao.getAll().map { it.syncId })
        // getBySyncId includes tombstones (sync needs them)
        assertTrue(dao.getBySyncId("a")!!.deleted)
    }
    @Test fun subtasksAndInList() = runTest {
        dao.upsert(Todo(syncId = "p", title = "P"))
        dao.upsert(Todo(syncId = "s2", title = "S2", parentSyncId = "p", position = 2.0))
        dao.upsert(Todo(syncId = "s1", title = "S1", parentSyncId = "p", position = 1.0))
        dao.upsert(Todo(syncId = "in", title = "In", listSyncId = "L"))
        assertEquals(listOf("s1", "s2"), dao.getSubtasks("p").map { it.syncId })
        assertEquals(listOf("s1", "s2"), dao.getSubtasksFlow("p").first().map { it.syncId })
        assertEquals(listOf("in"), dao.getInList("L").map { it.syncId })
        assertEquals(2.0, dao.maxPosition())
    }
    @Test fun updatedSinceAndCounts() = runTest {
        dao.upsertRaw(Todo(syncId = "x", title = "X", updatedAt = 5000L))
        dao.upsertRaw(Todo(syncId = "y", title = "Y", updatedAt = 1000L))
        assertEquals(listOf("x"), dao.getUpdatedSince(4000L).map { it.syncId })
        assertEquals(1, dao.countUpdatedSinceFlow(4000L).first())
        assertEquals(2, dao.countUpdatedSinceFlow(0L).first())
    }
    @Test fun deleteUnsyncedTombstonesKeepsSnapshotted() = runTest {
        dao.upsertRaw(Todo(syncId = "old", title = "Old", deleted = true, updatedAt = 100L))
        dao.upsertRaw(Todo(syncId = "snap", title = "Snap", deleted = true, updatedAt = 100L))
        snapDao.upsert(SyncSnapshot(syncId = "snap", kind = SyncSnapshot.KIND_TODO, contentHash = "h", localUpdatedAt = 1, remoteModified = 1, lastSyncedAt = 1))
        assertEquals(1, dao.deleteUnsyncedTombstones(1000L))
        assertNull(dao.getBySyncId("old"))
        assertNotNull(dao.getBySyncId("snap"))
        dao.deleteAll()
        assertTrue(dao.getAll().isEmpty())
    }
    @Test fun listDaoCrud() = runTest {
        listDao.upsert(TodoList(syncId = "l2", name = "Two", position = 2.0))
        listDao.upsert(TodoList(syncId = "l1", name = "One", position = 1.0))
        assertEquals(listOf("l1", "l2"), listDao.getAll().map { it.syncId })
        assertEquals(listOf("l1", "l2"), listDao.getAllFlow().first().map { it.syncId })
        assertEquals("One", listDao.getBySyncId("l1")?.name)
        listDao.upsertRaw(listDao.getBySyncId("l1")!!.copy(updatedAt = 9000L))
        assertTrue(listDao.getUpdatedSince(8000L).any { it.syncId == "l1" })
        assertEquals(2.0, listDao.maxPosition())
        listDao.deleteAll()
        assertTrue(listDao.getAll().isEmpty())
    }
    @Test fun snapshotDaoKinds() = runTest {
        snapDao.upsert(SyncSnapshot(syncId = "t1", kind = SyncSnapshot.KIND_TODO, contentHash = "h", localUpdatedAt = 1, remoteModified = 2, lastSyncedAt = 3))
        snapDao.upsert(SyncSnapshot(syncId = "l1", kind = SyncSnapshot.KIND_LIST, contentHash = "h", localUpdatedAt = 1, remoteModified = 2, lastSyncedAt = 3))
        snapDao.upsert(SyncSnapshot(syncId = "legacy", kind = "", contentHash = "h", localUpdatedAt = 1, remoteModified = 2, lastSyncedAt = 3))
        assertEquals(setOf("t1", "legacy"), snapDao.getAllExcept(SyncSnapshot.KIND_LIST).map { it.syncId }.toSet())
        assertEquals(listOf("l1"), snapDao.getByKind(SyncSnapshot.KIND_LIST).map { it.syncId })
        snapDao.deleteBySyncId("t1")
        assertEquals(setOf("legacy"), snapDao.getAllExcept(SyncSnapshot.KIND_LIST).map { it.syncId }.toSet())
        snapDao.deleteByKind(SyncSnapshot.KIND_LIST)
        assertTrue(snapDao.getByKind(SyncSnapshot.KIND_LIST).isEmpty())
    }
    @Test fun convertersRoundTrip() {
        val c = TodoConverters()
        assertEquals(listOf("a", "b"), c.toStringList(c.fromStringList(listOf("a", "b"))))
        assertEquals(emptyList(), c.toStringList("not json"))
        assertEquals(emptyList(), c.toStringList(c.fromStringList(emptyList())))
        assertEquals(listOf("x y", "😀"), c.toStringList(c.fromStringList(listOf("x y", "😀"))))
    }
    @Test fun settingsDefaultsAndSetters() = runTest {
        val s = TodoSettings(context)
        s.setDigestEnabled(true); assertTrue(s.digestEnabled.first())
        assertTrue(s.digestEnabledNow())
        s.setDigestMinutes(600); assertEquals(600L, s.digestMinutesNow())
        s.setCalendarMirror(true); assertTrue(s.calendarMirrorNow())
        s.markSynced(1234L)
        assertTrue(s.lastSyncAt.first() >= 1234L)
        s.setShowCompleted(false); assertFalse(s.showCompleted.first())
        assertNotNull(s.defaultView.first()); assertNotNull(s.defaultSort.first())
    }
    @Test fun newSyncIdFormat() {
        val a = newSyncId(); val b = newSyncId()
        assertEquals(32, a.length); assertTrue(a.all { it.isLetterOrDigit() }); assertTrue(a != b)
        assertEquals("todo", SyncSnapshot.KIND_TODO); assertEquals("todolist", SyncSnapshot.KIND_LIST)
    }
    @Test fun dbConfigsName() {
        // todoDbConfigs needs passphrase (AndroidKeyStore) - on Robolectric it may fail; just check DB_NAME constant
        assertEquals("todo-db", DB_NAME)
    }
}
