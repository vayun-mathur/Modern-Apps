package com.vayunmathur.todo.data

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
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

    @Query("SELECT * FROM Todo")
    abstract suspend fun getAllIncludingDeleted(): List<Todo>

    @Query("SELECT * FROM Todo WHERE id = :id AND deleted = 0")
    abstract suspend fun getById(id: Long): Todo?

    @Query("SELECT * FROM Todo WHERE syncId = :syncId")
    abstract suspend fun getBySyncId(syncId: String): Todo?

    @Query("SELECT * FROM Todo WHERE syncId = :syncId")
    abstract fun getBySyncIdFlow(syncId: String): Flow<Todo?>

    @Query("SELECT MAX(position) FROM Todo WHERE deleted = 0")
    abstract suspend fun maxPosition(): Double?

    @Query("SELECT * FROM Todo WHERE updatedAt > :since")
    abstract suspend fun getUpdatedSince(since: Long): List<Todo>

    /** Local write: bumps updatedAt so the change is picked up by sync. */
    suspend fun upsert(value: Todo): Long =
        upsertRaw(value.copy(updatedAt = System.currentTimeMillis()))

    /** Writes [value] verbatim. Only the relay sync uses this, to preserve a pulled timestamp. */
    @Upsert
    abstract suspend fun upsertRaw(value: Todo): Long

    @Delete
    abstract suspend fun delete(value: Todo): Int

    @Query("DELETE FROM Todo WHERE syncId = :syncId")
    abstract suspend fun deleteBySyncId(syncId: String): Int

    @Query("DELETE FROM Todo")
    abstract suspend fun deleteAll()

    /** Local bulk write: bumps updatedAt on every row so reorders reach sync. */
    suspend fun upsertAll(values: List<Todo>) {
        val now = System.currentTimeMillis()
        upsertAllRaw(values.map { it.copy(updatedAt = now) })
    }

    @Query(
        "DELETE FROM Todo WHERE deleted = 1 AND updatedAt < :before " +
            "AND syncId NOT IN (SELECT syncId FROM SyncSnapshot)",
    )
    abstract suspend fun deleteUnsyncedTombstones(before: Long): Int

    /** Writes [values] verbatim. Only the relay sync uses this. */
    @Upsert
    abstract suspend fun upsertAllRaw(values: List<Todo>)
}

@Dao
interface TodoSyncSnapshotDao {
    @Query("SELECT * FROM SyncSnapshot")
    suspend fun getAll(): List<SyncSnapshot>

    @Upsert
    suspend fun upsert(snapshot: SyncSnapshot)

    @Query("DELETE FROM SyncSnapshot WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("DELETE FROM SyncSnapshot")
    suspend fun deleteAll()
}

@Database(entities = [Todo::class, SyncSnapshot::class], version = 2, exportSchema = false)
abstract class TodoDatabase : RoomDatabase() {
    abstract fun todoDao(): TodoDao
    abstract fun syncSnapshotDao(): TodoSyncSnapshotDao

    companion object : DatabaseMigrations {
        // v2 adds the relay-carried `notes` + `createdAt` columns (drift fix):
        // the relay envelope always carried both, but the native model dropped
        // them on persist, so LWW on those fields never converged locally.
        override val migrations: List<Migration> = listOf(
            Migration(1, 2) {
                it.execSQL("ALTER TABLE `Todo` ADD COLUMN `notes` TEXT NOT NULL DEFAULT ''")
                it.execSQL("ALTER TABLE `Todo` ADD COLUMN `createdAt` INTEGER NOT NULL DEFAULT 0")
                it.execSQL("UPDATE `Todo` SET `createdAt` = `updatedAt` WHERE `createdAt` = 0")
            },
        )
    }
}
