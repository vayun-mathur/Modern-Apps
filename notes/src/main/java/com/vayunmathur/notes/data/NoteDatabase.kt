package com.vayunmathur.notes.data

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import com.vayunmathur.library.util.SyncStamp
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "notes-db"

/** Backup config for the in-app backup buttons. */
fun noteDbConfigs(context: Context): List<Pair<String, String>> =
    listOf(DB_NAME to DatabaseHelper(context).getPassphrase())

/** Room DAO for note queries, including sync and tombstone access. */
@Dao
abstract class NoteDao {
    @Query("SELECT * FROM Note WHERE deleted = 0")
    abstract fun getAllFlow(): Flow<List<Note>>

    @Query("SELECT * FROM Note WHERE deleted = 0")
    abstract suspend fun getAll(): List<Note>

    @Query("SELECT * FROM Note")
    abstract suspend fun getAllIncludingDeleted(): List<Note>

    @Query("SELECT * FROM Note WHERE id = :id AND deleted = 0")
    abstract fun getByIdFlow(id: Long): Flow<Note?>

    @Query("SELECT * FROM Note WHERE syncId = :syncId")
    abstract suspend fun getBySyncId(syncId: String): Note?

    @Query("SELECT * FROM Note WHERE updatedAt > :since")
    abstract suspend fun getUpdatedSince(since: Long): List<Note>

    /**
     * Highest stamp this store has minted or observed. The rows themselves
     * are the persisted hybrid-stamp high-water mark: a pulled remote stamp
     * lands via [upsertRaw], so the next local stamp (see [upsert]) is
     * automatically past it, and monotonicity survives process death with
     * no extra column.
     */
    @Query("SELECT MAX(updatedAt) FROM Note")
    abstract suspend fun maxUpdatedAt(): Long?

    /** Next hybrid stamp for a local write (see [SyncStamp]). */
    suspend fun nextStamp(): Long =
        SyncStamp.next(maxUpdatedAt() ?: 0L, System.currentTimeMillis())

    /** Local write: bumps updatedAt so the change is picked up by sync. */
    suspend fun upsert(value: Note): Long =
        upsertRaw(value.copy(updatedAt = nextStamp()))

    /** Writes [value] verbatim. Only the relay sync uses this, to preserve a pulled timestamp. */
    @Upsert
    abstract suspend fun upsertRaw(value: Note): Long

    @Delete
    abstract suspend fun delete(value: Note): Int

    @Query("DELETE FROM Note WHERE syncId = :syncId")
    abstract suspend fun deleteBySyncId(syncId: String): Int

    @Query("DELETE FROM Note")
    abstract suspend fun deleteAll()

    /**
     * Hard-removes tombstones older than [cutoffMillis] (see
     * [NotesRepository.purgeExpiredTombstones] for the horizon reasoning).
     * Live rows are never touched, however old they are.
     */
    @Query("DELETE FROM Note WHERE deleted = 1 AND updatedAt < :cutoffMillis")
    abstract suspend fun purgeTombstonesOlderThan(cutoffMillis: Long): Int

    /** Local batch write (list reorder, legacy import): bumps updatedAt like [upsert]. */
    suspend fun upsertAll(values: List<Note>) {
        val now = nextStamp()
        upsertAllRaw(values.map { it.copy(updatedAt = now) })
    }

    /** Writes [values] verbatim. Only the relay sync uses this. */
    @Upsert
    abstract suspend fun upsertAllRaw(values: List<Note>)
}

/** Room DAO for relay sync baseline snapshots. */
@Dao
interface NoteSyncSnapshotDao {
    @Query("SELECT * FROM SyncSnapshot")
    suspend fun getAll(): List<SyncSnapshot>

    @Upsert
    suspend fun upsert(snapshot: SyncSnapshot)

    @Query("DELETE FROM SyncSnapshot WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    /**
     * Drops baseline snapshots for tombstones expired at [cutoffMillis], so
     * purging a tombstone does not leave its snapshot row behind. Must run
     * before the tombstone rows themselves are deleted (it finds them via
     * the Note table).
     */
    @Query(
        "DELETE FROM SyncSnapshot WHERE syncId IN " +
            "(SELECT syncId FROM Note WHERE deleted = 1 AND updatedAt < :cutoffMillis)",
    )
    suspend fun deleteForTombstonesOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM SyncSnapshot")
    suspend fun deleteAll()
}

/** Room database holding notes and their sync snapshots. */
@Database(entities = [Note::class, SyncSnapshot::class], version = 4, exportSchema = true)
@ColumnTypeConverters(NoteConverters::class)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun syncSnapshotDao(): NoteSyncSnapshotDao

    /** Migrations for NoteDatabase. */
    companion object : DatabaseMigrations {
        // v2 adds the nullable `blocks` column (JSON list of NoteBlock). Existing
        // rows keep blocks = NULL and are read as a single text block.
        // v3 adds relay sync identity (mirrors passwords 2->3 backfill): syncId /
        // updatedAt / deleted on Note plus the SyncSnapshot baseline table.
        // v4 adds `tags` (#913), a JSON array of strings. NOT NULL DEFAULT '[]'
        // so every existing row reads back as untagged; no backfill needed.

        /** v3 -> v4: the `tags` column. Hand-written (exportSchema is off). */
        val MIGRATION_3_4 = Migration(3, 4) {
            it.execSQL("ALTER TABLE `Note` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '[]'")
        }

        override val migrations = listOf(
            Migration(1, 2) {
                it.execSQL("ALTER TABLE Note ADD COLUMN blocks TEXT")
            },
            Migration(2, 3) {
                it.execSQL("ALTER TABLE `Note` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''")
                it.execSQL("ALTER TABLE `Note` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                it.execSQL("ALTER TABLE `Note` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
                it.execSQL("UPDATE `Note` SET `syncId` = lower(hex(randomblob(16))) WHERE `syncId` = ''")
                it.execSQL("UPDATE `Note` SET `updatedAt` = ${System.currentTimeMillis()} WHERE `updatedAt` = 0")
                it.execSQL(
                    """CREATE TABLE IF NOT EXISTS `SyncSnapshot` (
                        `syncId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `localUpdatedAt` INTEGER NOT NULL,
                        `remoteModified` INTEGER NOT NULL,
                        `lastSyncedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`syncId`)
                    )""",
                )
            },
            MIGRATION_3_4,
        )
    }
}
