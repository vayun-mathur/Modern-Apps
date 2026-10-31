package com.vayunmathur.todo.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.todo.domain.Positions
import com.vayunmathur.todo.domain.TombstonePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow

class TodoRepository private constructor(
    context: Context,
    val io: CoroutineDispatcher = Dispatchers.IO,
) : RoomRepository<TodoDatabase>(context, TodoDatabase::class, DB_NAME) {

    /** Outlives any screen, so a write started on the way out of the editor still lands. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + io)

    private val dao get() = db.todoDao()
    private val snapshotDao get() = db.syncSnapshotDao()

    val todos: Flow<List<Todo>> get() = dao.getAllFlow()
    fun todoBySyncIdFlow(syncId: String): Flow<Todo?> = dao.getBySyncIdFlow(syncId)

    suspend fun getAll(): List<Todo> = dao.getAll()
    suspend fun getById(id: Long): Todo? = dao.getById(id)
    suspend fun getBySyncId(syncId: String): Todo? = dao.getBySyncId(syncId)
    suspend fun getUpdatedSince(since: Long): List<Todo> = dao.getUpdatedSince(since)

    suspend fun nextPosition(): Double = Positions.after(dao.maxPosition())

    /** Local write: bumps updatedAt so the change is picked up by sync. */
    suspend fun upsert(value: Todo): Long = dao.upsert(value)

    /** Local bulk write: bumps updatedAt on every row. */
    suspend fun upsertAll(values: List<Todo>) = dao.upsertAll(values)

    /** Writes [value] verbatim. Only the relay sync uses this, to preserve a pulled timestamp. */
    suspend fun upsertRaw(value: Todo): Long = dao.upsertRaw(value)
    suspend fun upsertAllRaw(values: List<Todo>) = dao.upsertAllRaw(values)

    /** Soft delete: the relay sync propagates the tombstone. */
    suspend fun tombstone(value: Todo) {
        dao.upsert(value.copy(deleted = true))
    }

    suspend fun tombstoneBySyncId(syncId: String): Int {
        val existing = dao.getBySyncId(syncId) ?: return 0
        if (!existing.deleted) dao.upsert(existing.copy(deleted = true))
        return 1
    }

    /** Removes every todo (including tombstones). Used when a relay vault is purged. */
    suspend fun purge() = dao.deleteAll()

    /** Housekeeping on launch: drop stale unsynced tombstones and repair colliding positions. */
    suspend fun maintain(now: Long = System.currentTimeMillis()) {
        dao.deleteUnsyncedTombstones(TombstonePolicy.cutoff(now))
        val ordered = dao.getAll().sortedWith(compareBy({ it.position }, { it.createdAt }))
        if (Positions.needsRenormalize(ordered.map { it.position })) {
            val keys = Positions.renormalized(ordered.size)
            dao.upsertAll(ordered.mapIndexed { i, t -> t.withPosition(keys[i]) })
        }
    }

    suspend fun getAllSnapshots(): List<SyncSnapshot> = snapshotDao.getAll()
    suspend fun upsertSnapshot(snapshot: SyncSnapshot) = snapshotDao.upsert(snapshot)
    suspend fun deleteSnapshotBySyncId(syncId: String) = snapshotDao.deleteBySyncId(syncId)
    suspend fun deleteAllSnapshots() = snapshotDao.deleteAll()

    companion object {
        @Volatile private var instance: TodoRepository? = null
        fun get(context: Context): TodoRepository =
            instance ?: synchronized(this) {
                instance ?: TodoRepository(context).also { instance = it }
            }
    }
}
