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

/**
 * The single owner of the todo database. Task operations live here; list operations are in
 * `TodoRepositoryLists.kt` and the relay-only surface in `TodoRepositoryRelay.kt`.
 */
class TodoRepository private constructor(
    context: Context,
    val io: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository<TodoDatabase>(context, TodoDatabase::class, DB_NAME) {

    /** Outlives any screen, so a write started on the way out of the editor still lands. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + io)

    internal val dao get() = db.todoDao()
    internal val listDao get() = db.todoListDao()
    internal val snapshotDao get() = db.syncSnapshotDao()

    val todos: Flow<List<Todo>> get() = dao.getAllFlow()
    val lists: Flow<List<TodoList>> get() = listDao.getAllFlow()
    fun todoBySyncIdFlow(syncId: String): Flow<Todo?> = dao.getBySyncIdFlow(syncId)
    fun subtasksFlow(parentSyncId: String): Flow<List<Todo>> = dao.getSubtasksFlow(parentSyncId)
    fun pendingSyncCount(since: Long): Flow<Int> = dao.countUpdatedSinceFlow(since)

    suspend fun <R> inTransaction(block: suspend () -> R): R = db.withWriteTransaction { block() }

    // --- Tasks ---

    suspend fun getAll(): List<Todo> = dao.getAll()
    suspend fun getById(id: Long): Todo? = dao.getById(id)
    suspend fun getBySyncId(syncId: String): Todo? = dao.getBySyncId(syncId)
    suspend fun getSubtasks(parentSyncId: String): List<Todo> = dao.getSubtasks(parentSyncId)

    suspend fun nextPosition(): Double = Positions.after(dao.maxPosition())

    /** Local write: bumps updatedAt so the change is picked up by sync. */
    suspend fun upsert(value: Todo): Long = dao.upsert(value)

    /** Local bulk write: bumps updatedAt on every row. */
    suspend fun upsertAll(values: List<Todo>) = dao.upsertAll(values)

    /** Soft delete: the relay sync propagates the tombstone. Subtasks go with their parent. */
    suspend fun tombstone(value: Todo) = inTransaction {
        dao.upsert(value.copy(deleted = true))
        dao.upsertAll(dao.getSubtasks(value.syncId).map { it.copy(deleted = true) })
    }

    /**
     * Undoes [tombstone]: restores the task and the subtasks the delete cascaded to.
     * Only rows still marked deleted are touched, so live subtasks keep their timestamps.
     * (A subtask deleted on its own before its parent was deleted comes back too — the
     * rows carry no deletion timestamps to tell the two cases apart.)
     */
    suspend fun restore(value: Todo) = inTransaction {
        dao.upsert(value.copy(deleted = false))
        dao.upsertAll(
            dao.getSubtasksIncludingDeleted(value.syncId)
                .filter { it.deleted }
                .map { it.copy(deleted = false) },
        )
    }

    /**
     * Sets the done state. Completing a repeating task rolls it to the next occurrence and
     * reopens its subtasks; returns the stored row.
     */
    suspend fun setDone(todo: Todo, done: Boolean, now: Long = System.currentTimeMillis()): Todo = inTransaction {
        val updated = if (done) {
            Completion.complete(todo, now, TimeZone.currentSystemDefault())
        } else {
            Completion.uncomplete(todo)
        }
        dao.upsert(updated)
        if (Completion.rolledForward(todo, updated)) {
            dao.upsertAll(dao.getSubtasks(todo.syncId).filter { it.done }.map { Completion.uncomplete(it) })
        }
        updated
    }

    suspend fun addSubtask(parent: Todo, title: String): Long {
        val last = dao.getSubtasks(parent.syncId).maxOfOrNull { it.position }
        return dao.upsert(
            Todo(
                title = title,
                parentSyncId = parent.syncId,
                listSyncId = parent.listSyncId,
                position = Positions.after(last),
            ),
        )
    }

    /** Reopens every done subtask, after the parent rolled to its next occurrence. */
    suspend fun reopenSubtasks(parentSyncId: String) {
        dao.upsertAll(dao.getSubtasks(parentSyncId).filter { it.done }.map { Completion.uncomplete(it) })
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

    companion object {
        @Volatile private var instance: TodoRepository? = null
        fun get(context: Context): TodoRepository =
            instance ?: synchronized(this) {
                instance ?: TodoRepository(context).also { instance = it }
            }
    }
}
