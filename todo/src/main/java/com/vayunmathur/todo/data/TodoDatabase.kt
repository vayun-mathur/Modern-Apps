package com.vayunmathur.todo.data

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "todo-db"

/** Backup config shared by [AppBackupAgent][com.vayunmathur.todo.platform.AppBackupAgent] and the in-app backup buttons. */
fun todoDbConfigs(context: Context): List<Pair<String, String>> =
    listOf(DB_NAME to DatabaseHelper(context).getPassphrase())

@Dao
abstract class TodoDao {
    @Query("SELECT * FROM Todo WHERE deleted = 0 ORDER BY position")
    abstract fun getAllFlow(): Flow<List<Todo>>

    @Query("SELECT * FROM Todo WHERE deleted = 0 ORDER BY position")
    abstract suspend fun getAll(): List<Todo>

    @Query("SELECT * FROM Todo WHERE id = :id AND deleted = 0")
    abstract suspend fun getById(id: Long): Todo?

    @Query("SELECT * FROM Todo WHERE syncId = :syncId")
    abstract suspend fun getBySyncId(syncId: String): Todo?

    @Query("SELECT * FROM Todo WHERE syncId = :syncId")
    abstract fun getBySyncIdFlow(syncId: String): Flow<Todo?>

    @Query("SELECT * FROM Todo WHERE parentSyncId = :parentSyncId AND deleted = 0 ORDER BY position")
    abstract suspend fun getSubtasks(parentSyncId: String): List<Todo>

    @Query("SELECT * FROM Todo WHERE updatedAt > :since")
    abstract suspend fun getUpdatedSince(since: Long): List<Todo>

    @Query("SELECT MAX(position) FROM Todo WHERE deleted = 0")
    abstract suspend fun maxPosition(): Double?

    @Query("SELECT * FROM Todo WHERE deleted = 0 AND listSyncId = :listSyncId")
    abstract suspend fun getInList(listSyncId: String): List<Todo>

    /** Local write: bumps updatedAt so the change is picked up by sync. */
    suspend fun upsert(value: Todo): Long =
        upsertRaw(value.copy(updatedAt = System.currentTimeMillis()))

    /** Local bulk write: bumps updatedAt on every row so reorders reach sync. */
    suspend fun upsertAll(values: List<Todo>) {
        val now = System.currentTimeMillis()
        upsertAllRaw(values.map { it.copy(updatedAt = now) })
    }

    /** Writes [value] verbatim. Only the relay sync uses this, to preserve a pulled timestamp. */
    @Upsert
    abstract suspend fun upsertRaw(value: Todo): Long

    /** Writes [values] verbatim. Only the relay sync uses this. */
    @Upsert
    abstract suspend fun upsertAllRaw(values: List<Todo>)

    @Query(
        "DELETE FROM Todo WHERE deleted = 1 AND updatedAt < :before " +
            "AND syncId NOT IN (SELECT syncId FROM SyncSnapshot)",
    )
    abstract suspend fun deleteUnsyncedTombstones(before: Long): Int

    @Query("DELETE FROM Todo")
    abstract suspend fun deleteAll()
}

@Dao
abstract class TodoListDao {
    @Query("SELECT * FROM TodoList WHERE deleted = 0 ORDER BY position")
    abstract fun getAllFlow(): Flow<List<TodoList>>

    @Query("SELECT * FROM TodoList WHERE deleted = 0 ORDER BY position")
    abstract suspend fun getAll(): List<TodoList>

    @Query("SELECT * FROM TodoList WHERE syncId = :syncId")
    abstract suspend fun getBySyncId(syncId: String): TodoList?

    @Query("SELECT * FROM TodoList WHERE updatedAt > :since")
    abstract suspend fun getUpdatedSince(since: Long): List<TodoList>

    @Query("SELECT MAX(position) FROM TodoList WHERE deleted = 0")
    abstract suspend fun maxPosition(): Double?

    suspend fun upsert(value: TodoList): Long =
        upsertRaw(value.copy(updatedAt = System.currentTimeMillis()))

    suspend fun upsertAll(values: List<TodoList>) {
        val now = System.currentTimeMillis()
        upsertAllRaw(values.map { it.copy(updatedAt = now) })
    }

    @Upsert
    abstract suspend fun upsertRaw(value: TodoList): Long

    @Upsert
    abstract suspend fun upsertAllRaw(values: List<TodoList>)

    @Query("DELETE FROM TodoList")
    abstract suspend fun deleteAll()
}

@Dao
interface TodoSyncSnapshotDao {
    @Query("SELECT * FROM SyncSnapshot WHERE kind = :kind")
    suspend fun getByKind(kind: String): List<SyncSnapshot>

    /** Todo snapshots predate the kind column being filled in, so match "anything but lists". */
    @Query("SELECT * FROM SyncSnapshot WHERE kind != :excludedKind")
    suspend fun getAllExcept(excludedKind: String): List<SyncSnapshot>

    @Upsert
    suspend fun upsert(snapshot: SyncSnapshot)

    @Query("DELETE FROM SyncSnapshot WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("DELETE FROM SyncSnapshot WHERE kind = :kind")
    suspend fun deleteByKind(kind: String)

    @Query("DELETE FROM SyncSnapshot WHERE kind != :excludedKind")
    suspend fun deleteAllExcept(excludedKind: String)
}

@Database(entities = [Todo::class, TodoList::class, SyncSnapshot::class], version = 3, exportSchema = true)
@ColumnTypeConverters(TodoConverters::class)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun todoListDao(): TodoListDao
    abstract fun syncSnapshotDao(): TodoSyncSnapshotDao

    companion object : DatabaseMigrations {
        override val migrations: List<Migration> = TodoMigrations.all
    }
}
