package com.vayunmathur.todo.data

import android.content.Context
import androidx.room3.withWriteTransaction
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.todo.domain.Completion
import com.vayunmathur.todo.domain.Positions
import com.vayunmathur.todo.domain.TombstonePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.TimeZone

class TodoRepository private constructor(
    context: Context,
    val io: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository<TodoDatabase>(context, TodoDatabase::class, DB_NAME) {

    /** Outlives any screen, so a write started on the way out of the editor still lands. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + io)

    private val dao get() = db.todoDao()
    private val listDao get() = db.todoListDao()
    private val snapshotDao get() = db.syncSnapshotDao()

    val todos: Flow<List<Todo>> get() = dao.getAllFlow()
    val lists: Flow<List<TodoList>> get() = listDao.getAllFlow()
    fun todoBySyncIdFlow(syncId: String): Flow<Todo?> = dao.getBySyncIdFlow(syncId)

    suspend fun <R> inTransaction(block: suspend () -> R): R = db.withWriteTransaction { block() }

    // --- Tasks ---

    suspend fun getAll(): List<Todo> = dao.getAll()
    suspend fun getById(id: Long): Todo? = dao.getById(id)
    suspend fun getBySyncId(syncId: String): Todo? = dao.getBySyncId(syncId)
    suspend fun getSubtasks(parentSyncId: String): List<Todo> = dao.getSubtasks(parentSyncId)
    suspend fun getUpdatedSince(since: Long): List<Todo> = dao.getUpdatedSince(since)

    suspend fun nextPosition(): Double = Positions.after(dao.maxPosition())

    /** Local write: bumps updatedAt so the change is picked up by sync. */
    suspend fun upsert(value: Todo): Long = dao.upsert(value)

    /** Local bulk write: bumps updatedAt on every row. */
    suspend fun upsertAll(values: List<Todo>) = dao.upsertAll(values)

    /** Writes [value] verbatim. Only the relay sync uses this, to preserve a pulled timestamp. */
    suspend fun upsertRaw(value: Todo): Long = dao.upsertRaw(value)

    /** Soft delete: the relay sync propagates the tombstone. Subtasks go with their parent. */
    suspend fun tombstone(value: Todo) = inTransaction {
        dao.upsert(value.copy(deleted = true))
        dao.upsertAll(dao.getSubtasks(value.syncId).map { it.copy(deleted = true) })
    }

    suspend fun restore(value: Todo) = inTransaction {
        dao.upsert(value.copy(deleted = false))
    }

    suspend fun tombstoneBySyncId(syncId: String): Int {
        val existing = dao.getBySyncId(syncId) ?: return 0
        if (!existing.deleted) dao.upsert(existing.copy(deleted = true))
        return 1
    }

    /**
     * Sets the done state. Completing a repeating task rolls it to the next occurrence and
     * reopens its subtasks; returns the stored row.
     */
    suspend fun setDone(todo: Todo, done: Boolean, now: Long = System.currentTimeMillis()): Todo = inTransaction {
        val updated = if (done) Completion.complete(todo, now, TimeZone.currentSystemDefault()) else Completion.uncomplete(todo)
        dao.upsert(updated)
        if (Completion.rolledForward(todo, updated)) {
            dao.upsertAll(dao.getSubtasks(todo.syncId).filter { it.done }.map { Completion.uncomplete(it) })
        }
        updated
    }

    suspend fun duplicate(todo: Todo): Todo = inTransaction {
        val copy = todo.copy(
            id = 0,
            syncId = newSyncId(),
            title = todo.title,
            done = false,
            completedAt = null,
            position = nextPosition(),
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.upsert(copy)
        dao.getSubtasks(todo.syncId).forEach { sub ->
            dao.upsert(sub.copy(id = 0, syncId = newSyncId(), parentSyncId = copy.syncId, createdAt = copy.createdAt))
        }
        copy.copy(id = id)
    }

    /** Removes every todo (including tombstones). Used when a relay vault is purged. */
    suspend fun purge() = dao.deleteAll()

    // --- Lists ---

    suspend fun getLists(): List<TodoList> = listDao.getAll()
    suspend fun getListBySyncId(syncId: String): TodoList? = listDao.getBySyncId(syncId)
    suspend fun getListsUpdatedSince(since: Long): List<TodoList> = listDao.getUpdatedSince(since)
    suspend fun nextListPosition(): Double = Positions.after(listDao.maxPosition())

    suspend fun upsertList(value: TodoList): Long = listDao.upsert(value)
    suspend fun upsertLists(values: List<TodoList>) = listDao.upsertAll(values)
    suspend fun upsertListRaw(value: TodoList): Long = listDao.upsertRaw(value)

    /** Deletes a list; its tasks move to the Inbox. */
    suspend fun tombstoneList(list: TodoList) = inTransaction {
        listDao.upsert(list.copy(deleted = true))
        dao.upsertAll(dao.getInList(list.syncId).map { it.copy(listSyncId = null, section = null) })
    }

    suspend fun tombstoneListBySyncId(syncId: String): Int {
        val existing = listDao.getBySyncId(syncId) ?: return 0
        if (!existing.deleted) listDao.upsert(existing.copy(deleted = true))
        return 1
    }

    suspend fun purgeLists() = listDao.deleteAll()

    // --- Housekeeping ---

    /** On launch: drop stale unsynced tombstones and repair colliding positions. */
    suspend fun maintain(now: Long = System.currentTimeMillis()) {
        dao.deleteUnsyncedTombstones(TombstonePolicy.cutoff(now))
        val ordered = dao.getAll().sortedWith(compareBy({ it.position }, { it.createdAt }))
        if (Positions.needsRenormalize(ordered.map { it.position })) {
            val keys = Positions.renormalized(ordered.size)
            dao.upsertAll(ordered.mapIndexed { i, t -> t.withPosition(keys[i]) })
        }
    }

    // --- Relay snapshots ---

    suspend fun getTodoSnapshots(): List<SyncSnapshot> = snapshotDao.getAllExcept(SyncSnapshot.KIND_LIST)
    suspend fun getListSnapshots(): List<SyncSnapshot> = snapshotDao.getByKind(SyncSnapshot.KIND_LIST)
    suspend fun upsertSnapshot(snapshot: SyncSnapshot) = snapshotDao.upsert(snapshot)
    suspend fun deleteSnapshotBySyncId(syncId: String) = snapshotDao.deleteBySyncId(syncId)
    suspend fun deleteTodoSnapshots() = snapshotDao.deleteAllExcept(SyncSnapshot.KIND_LIST)
    suspend fun deleteListSnapshots() = snapshotDao.deleteByKind(SyncSnapshot.KIND_LIST)

    companion object {
        @Volatile private var instance: TodoRepository? = null
        fun get(context: Context): TodoRepository =
            instance ?: synchronized(this) {
                instance ?: TodoRepository(context).also { instance = it }
            }
    }
}
