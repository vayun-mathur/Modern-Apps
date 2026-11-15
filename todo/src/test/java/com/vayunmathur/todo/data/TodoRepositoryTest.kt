package com.vayunmathur.todo.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.domain.Recurrence
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

/**
 * Genuine [TodoRepository] behaviour (including the relay/list extension files)
 * against an in-memory Room database. See [TestTodoRepository] for how the real
 * repository is obtained without SQLCipher.
 */
@RunWith(RobolectricTestRunner::class)
class TodoRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val harness = lazy { TestTodoRepository.create(context) }
    private val repo: TodoRepository get() = harness.value.repository
    private val db: TodoDatabase get() = harness.value.database

    @AfterTest fun tearDown() {
        if (harness.isInitialized()) harness.value.close()
    }

    private suspend fun seed(vararg todos: Todo): List<Todo> {
        todos.forEach { repo.upsert(it) }
        return repo.getAll()
    }

    @Test fun upsertGetByIdAndSyncIdRoundTrip() = runTest {
        val id = repo.upsert(Todo(syncId = "a", title = "Alpha", notes = "n"))
        assertTrue(id > 0)
        val stored = repo.getById(id)
        assertEquals("Alpha", stored?.title)
        assertEquals("a", repo.getBySyncId("a")?.syncId)
        assertNull(repo.getById(999_999))
        assertNull(repo.getBySyncId("missing"))
        // Updating the same row keeps the id and changes the title.
        repo.upsert(stored!!.copy(title = "Alpha 2"))
        assertEquals("Alpha 2", repo.getById(id)?.title)
        assertEquals(1, repo.getAll().size)
    }

    @Test fun todosFlowEmitsOrderedByPosition() = runTest {
        repo.upsert(Todo(syncId = "b", title = "B", position = 20.0))
        repo.upsert(Todo(syncId = "a", title = "A", position = 10.0))
        assertEquals(listOf("a", "b"), repo.todos.first().map { it.syncId })
        assertEquals(listOf("a", "b"), repo.getAll().map { it.syncId })
    }

    @Test fun nextPositionIncreasesAfterMax() = runTest {
        val first = repo.nextPosition()
        repo.upsert(Todo(syncId = "a", title = "A", position = first))
        val second = repo.nextPosition()
        assertTrue(second > first)
        repo.upsert(Todo(syncId = "b", title = "B", position = second))
        assertEquals(listOf("a", "b"), repo.getAll().map { it.syncId })
    }

    @Test fun upsertAllWritesEveryRow() = runTest {
        repo.upsertAll(
            listOf(
                Todo(syncId = "a", title = "A", position = 1.0),
                Todo(syncId = "b", title = "B", position = 2.0),
                Todo(syncId = "c", title = "C", position = 3.0),
            ),
        )
        assertEquals(listOf("a", "b", "c"), repo.getAll().map { it.syncId })
    }

    @Test fun tombstoneCascadesToSubtasksAndRestoreUndoesIt() = runTest {
        seed(
            Todo(syncId = "p", title = "Parent"),
            Todo(syncId = "s1", title = "Sub 1", parentSyncId = "p"),
            Todo(syncId = "s2", title = "Sub 2", parentSyncId = "p"),
            Todo(syncId = "other", title = "Other"),
        )
        val parent = repo.getBySyncId("p")!!
        repo.tombstone(parent)
        assertTrue(repo.getBySyncId("p")!!.deleted)
        assertTrue(repo.getBySyncId("s1")!!.deleted)
        assertTrue(repo.getBySyncId("s2")!!.deleted)
        assertFalse(repo.getBySyncId("other")!!.deleted)
        assertEquals(listOf("other"), repo.getAll().map { it.syncId })

        repo.restore(repo.getBySyncId("p")!!)
        assertFalse(repo.getBySyncId("p")!!.deleted)
        assertEquals(setOf("p", "s1", "s2", "other"), repo.getAll().map { it.syncId }.toSet())
    }

    @Test fun restoreLeavesAlreadyLiveSubtasksUntouched() = runTest {
        seed(
            Todo(syncId = "p", title = "Parent"),
            Todo(syncId = "live", title = "Live", parentSyncId = "p"),
        )
        val liveBefore = repo.getBySyncId("live")!!
        repo.tombstone(repo.getBySyncId("p")!!)
        // Manually revive one subtask, then restore the parent: the live row must not
        // be rewritten as part of the restore cascade.
        db.todoDao().upsert(liveBefore.copy(deleted = false))
        repo.restore(repo.getBySyncId("p")!!)
        assertFalse(repo.getBySyncId("live")!!.deleted)
        assertFalse(repo.getBySyncId("p")!!.deleted)
    }

    @Test fun setDoneCompletesPlainTaskAndUncompleteReopens() = runTest {
        seed(Todo(syncId = "a", title = "A"))
        val todo = repo.getBySyncId("a")!!
        val done = repo.setDone(todo, true, now = 1_700_000_000_000L)
        assertTrue(done.done)
        assertEquals(1_700_000_000_000L, done.completedAt)
        assertTrue(repo.getBySyncId("a")!!.done)

        val reopened = repo.setDone(repo.getBySyncId("a")!!, false)
        assertFalse(reopened.done)
        assertNull(reopened.completedAt)
    }

    @Test fun setDoneOnRepeatingTaskRollsForwardAndReopensSubtasks() = runTest {
        val due = System.currentTimeMillis() - 86_400_000L
        seed(
            Todo(
                syncId = "r", title = "Daily", dueAt = due, dueAllDay = true,
                recurrence = Recurrence.Daily(1).serialize(),
            ),
            Todo(syncId = "sub", title = "Sub", parentSyncId = "r", done = true, completedAt = due),
        )
        val rolled = repo.setDone(repo.getBySyncId("r")!!, true)
        // A repeating task is not finished: it moves to its next occurrence.
        assertFalse(rolled.done)
        assertNotNull(rolled.dueAt)
        assertTrue(rolled.dueAt!! > due)
        // Its completed subtasks reopen for the new occurrence.
        assertFalse(repo.getBySyncId("sub")!!.done)
    }

    @Test fun addSubtaskInheritsListAndAppendsPosition() = runTest {
        seed(Todo(syncId = "p", title = "Parent", listSyncId = "work"))
        repo.addSubtask(repo.getBySyncId("p")!!, "First sub")
        repo.addSubtask(repo.getBySyncId("p")!!, "Second sub")
        val subs = repo.getSubtasks("p")
        assertEquals(listOf("First sub", "Second sub"), subs.map { it.title })
        assertTrue(subs.all { it.parentSyncId == "p" })
        assertTrue(subs.all { it.listSyncId == "work" })
        assertTrue(subs[1].position > subs[0].position)
        assertEquals(subs.map { it.syncId }, repo.subtasksFlow("p").first().map { it.syncId })
    }

    @Test fun reopenSubtasksOnlyReopensDoneOnes() = runTest {
        seed(
            Todo(syncId = "p", title = "Parent"),
            Todo(syncId = "d", title = "Done sub", parentSyncId = "p", done = true, completedAt = 1L),
            Todo(syncId = "o", title = "Open sub", parentSyncId = "p", done = false),
        )
        repo.reopenSubtasks("p")
        assertFalse(repo.getBySyncId("d")!!.done)
        assertNull(repo.getBySyncId("d")!!.completedAt)
        assertFalse(repo.getBySyncId("o")!!.done)
    }

    @Test fun duplicateCopiesTaskAndSubtasksWithFreshIdentities() = runTest {
        seed(
            Todo(syncId = "p", title = "Original", done = true, completedAt = 5L),
            Todo(syncId = "s", title = "Sub", parentSyncId = "p"),
        )
        val copy = repo.duplicate(repo.getBySyncId("p")!!)
        assertTrue(copy.id != 0L)
        assertTrue(copy.syncId != "p")
        assertEquals("Original", copy.title)
        assertFalse(copy.done)
        assertNull(copy.completedAt)
        val copiedSubs = repo.getSubtasks(copy.syncId)
        assertEquals(1, copiedSubs.size)
        assertEquals("Sub", copiedSubs[0].title)
        assertTrue(copiedSubs[0].syncId != "s")
        // The original is untouched.
        assertTrue(repo.getBySyncId("p")!!.done)
        assertEquals(1, repo.getSubtasks("p").size)
    }

    @Test fun pendingSyncCountCountsRowsUpdatedAfterSince() = runTest {
        db.todoDao().upsertRaw(Todo(syncId = "old", title = "Old", updatedAt = 100L))
        db.todoDao().upsertRaw(Todo(syncId = "new", title = "New", updatedAt = 5_000L))
        assertEquals(1, repo.pendingSyncCount(1_000L).first())
        assertEquals(2, repo.pendingSyncCount(0L).first())
        assertEquals(0, repo.pendingSyncCount(9_999L).first())
    }

    @Test fun maintainDeletesStaleUnsyncedTombstonesAndRenormalizes() = runTest {
        val now = System.currentTimeMillis()
        val old = now - 40L * 24 * 60 * 60 * 1000
        db.todoDao().upsertRaw(Todo(syncId = "stale", title = "Stale", deleted = true, updatedAt = old, position = 1.0))
        db.todoDao().upsertRaw(Todo(syncId = "a", title = "A", updatedAt = now, position = 1.0))
        db.todoDao().upsertRaw(Todo(syncId = "b", title = "B", updatedAt = now, position = 1.0))
        repo.maintain(now)
        // Stale tombstone with no sync snapshot is collected.
        assertNull(repo.getBySyncId("stale"))
        // Colliding positions are spread back out, preserving order.
        val all = repo.getAll()
        assertEquals(listOf("a", "b"), all.map { it.syncId })
        assertTrue(all.zipWithNext().all { (x, y) -> y.position > x.position })
    }

    @Test fun maintainKeepsSnapshottedTombstone() = runTest {
        val now = System.currentTimeMillis()
        val old = now - 40L * 24 * 60 * 60 * 1000
        db.todoDao().upsertRaw(Todo(syncId = "kept", title = "Kept", deleted = true, updatedAt = old))
        repo.upsertSnapshot(
            SyncSnapshot(
                syncId = "kept", kind = SyncSnapshot.KIND_TODO, contentHash = "h",
                localUpdatedAt = old, remoteModified = old, lastSyncedAt = old,
            ),
        )
        repo.maintain(now)
        assertNotNull(repo.getBySyncId("kept"))
    }

    @Test fun inTransactionRollsThroughRepositoryWrites() = runTest {
        val count = repo.inTransaction {
            repo.upsert(Todo(syncId = "t1", title = "One"))
            repo.upsert(Todo(syncId = "t2", title = "Two"))
            repo.getAll().size
        }
        assertEquals(2, count)
    }

    // --- Lists (TodoRepositoryLists.kt) ---

    @Test fun listCrudNextPositionAndFlow() = runTest {
        repo.upsertList(TodoList(syncId = "l2", name = "Two"))
        repo.upsertList(TodoList(syncId = "l1", name = "One"))
        assertEquals(setOf("l1", "l2"), repo.getLists().map { it.syncId }.toSet())
        assertEquals("One", repo.getListBySyncId("l1")?.name)
        assertNull(repo.getListBySyncId("nope"))
        assertEquals(setOf("l1", "l2"), repo.lists.first().map { it.syncId }.toSet())
        val pos = repo.nextListPosition()
        repo.upsertList(TodoList(syncId = "l3", name = "Three", position = pos))
        assertTrue(repo.nextListPosition() > pos)
    }

    @Test fun upsertListsWritesAll() = runTest {
        repo.upsertLists(
            listOf(
                TodoList(syncId = "a", name = "A", position = 1.0),
                TodoList(syncId = "b", name = "B", position = 2.0),
            ),
        )
        assertEquals(listOf("a", "b"), repo.getLists().map { it.syncId })
    }

    @Test fun tombstoneListMovesItsTasksToInbox() = runTest {
        repo.upsertList(TodoList(syncId = "work", name = "Work"))
        seed(
            Todo(syncId = "inlist", title = "In list", listSyncId = "work", section = "Now"),
            Todo(syncId = "inbox", title = "Inbox"),
        )
        repo.tombstoneList(repo.getListBySyncId("work")!!)
        assertTrue(db.todoListDao().getBySyncId("work")!!.deleted)
        val moved = repo.getBySyncId("inlist")!!
        assertNull(moved.listSyncId)
        assertNull(moved.section)
        assertEquals("Inbox", repo.getBySyncId("inbox")!!.title)
    }

    // --- Relay surface (TodoRepositoryRelay.kt) ---

    @Test fun relayUpdatedSinceAndUpsertRawPreservesTimestamp() = runTest {
        repo.upsertRaw(Todo(syncId = "raw", title = "Raw", updatedAt = 1_234L))
        assertEquals(1_234L, repo.getBySyncId("raw")!!.updatedAt)
        assertEquals(listOf("raw"), repo.getUpdatedSince(1_000L).map { it.syncId })
        assertTrue(repo.getUpdatedSince(2_000L).isEmpty())
        // A normal local upsert bumps updatedAt (contrast with the raw relay write).
        repo.upsert(repo.getBySyncId("raw")!!.copy(title = "Local"))
        assertTrue(repo.getBySyncId("raw")!!.updatedAt > 1_234L)
    }

    @Test fun relayTombstoneBySyncIdHandlesMissingAndAlreadyDeleted() = runTest {
        assertEquals(0, repo.tombstoneBySyncId("missing"))
        repo.upsertRaw(Todo(syncId = "x", title = "X"))
        assertEquals(1, repo.tombstoneBySyncId("x"))
        assertTrue(repo.getBySyncId("x")!!.deleted)
        // Already tombstoned still reports the row exists.
        assertEquals(1, repo.tombstoneBySyncId("x"))
    }

    @Test fun relayPurgeRemovesAllTodosIncludingTombstones() = runTest {
        repo.upsertRaw(Todo(syncId = "a", title = "A"))
        repo.upsertRaw(Todo(syncId = "b", title = "B", deleted = true))
        repo.purge()
        assertNull(repo.getBySyncId("a"))
        assertNull(repo.getBySyncId("b"))
        assertTrue(repo.getAll().isEmpty())
    }

    @Test fun relayListSurface() = runTest {
        repo.upsertListRaw(TodoList(syncId = "l", name = "List", updatedAt = 500L))
        assertEquals(500L, repo.getListBySyncId("l")!!.updatedAt)
        assertEquals(listOf("l"), repo.getListsUpdatedSince(100L).map { it.syncId })
        assertTrue(repo.getListsUpdatedSince(900L).isEmpty())
        assertEquals(0, repo.tombstoneListBySyncId("missing"))
        assertEquals(1, repo.tombstoneListBySyncId("l"))
        assertTrue(db.todoListDao().getBySyncId("l")!!.deleted)
        repo.purgeLists()
        assertNull(repo.getListBySyncId("l"))
    }

    @Test fun relaySnapshotSurfaceSeparatesKinds() = runTest {
        repo.upsertSnapshot(
            SyncSnapshot(
                syncId = "t", kind = SyncSnapshot.KIND_TODO, contentHash = "ht",
                localUpdatedAt = 1L, remoteModified = 2L, lastSyncedAt = 3L,
            ),
        )
        repo.upsertSnapshot(
            SyncSnapshot(
                syncId = "l", kind = SyncSnapshot.KIND_LIST, contentHash = "hl",
                localUpdatedAt = 1L, remoteModified = 2L, lastSyncedAt = 3L,
            ),
        )
        assertEquals(listOf("t"), repo.getTodoSnapshots().map { it.syncId })
        assertEquals(listOf("l"), repo.getListSnapshots().map { it.syncId })
        repo.deleteSnapshotBySyncId("t")
        assertTrue(repo.getTodoSnapshots().isEmpty())
        repo.deleteTodoSnapshots()
        repo.deleteListSnapshots()
        assertTrue(repo.getListSnapshots().isEmpty())
    }

    @Test fun todoBySyncIdFlowFollowsWrites() = runTest {
        repo.upsert(Todo(syncId = "f", title = "Before"))
        assertEquals("Before", repo.todoBySyncIdFlow("f").first()?.title)
        repo.upsert(repo.getBySyncId("f")!!.copy(title = "After"))
        assertEquals("After", repo.todoBySyncIdFlow("f").first()?.title)
        assertNull(repo.todoBySyncIdFlow("absent").first())
    }
}
