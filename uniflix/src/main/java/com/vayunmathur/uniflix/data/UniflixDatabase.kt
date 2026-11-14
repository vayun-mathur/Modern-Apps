package com.vayunmathur.uniflix.data

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DefaultConverters
import kotlinx.coroutines.flow.Flow

/** A TMDB title saved to the watchlist. */
@Entity
data class WatchlistEntry(
    @PrimaryKey val key: String,
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val posterPath: String?,
    val addedAt: Long,
)

/** Resume position per title (per episode for series). */
@Entity
data class ContinueWatchingEntry(
    @PrimaryKey val key: String,
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val posterPath: String?,
    val season: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
)

/** A user-created list. */
@Entity
data class UserList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
)

/** A TMDB title filed in a user list. */
@Entity
data class UserListItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val listId: Long,
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val posterPath: String?,
    val position: Int = 0,
)

/** A local note/comment on a title. */
@Entity
data class TitleNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tmdbId: Int,
    val mediaType: String,
    val body: String,
    val score: Int = 0,
    val createdAt: Long = 0L,
)

/** Contract for watchlist dao. */
@Dao
interface WatchlistDao {
    @Query("SELECT * FROM WatchlistEntry ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<WatchlistEntry>>

    @Query("SELECT * FROM WatchlistEntry WHERE `key` = :key")
    suspend fun getByKey(key: String): WatchlistEntry?

    @Upsert
    suspend fun upsert(value: WatchlistEntry)

    @Query("DELETE FROM WatchlistEntry WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}

/** Contract for continue watching dao. */
@Dao
interface ContinueWatchingDao {
    @Query("SELECT * FROM ContinueWatchingEntry ORDER BY updatedAt DESC")
    fun getAllFlow(): Flow<List<ContinueWatchingEntry>>

    @Query("SELECT positionMs FROM ContinueWatchingEntry WHERE `key` = :key")
    suspend fun getPosition(key: String): Long?

    @Upsert
    suspend fun upsert(value: ContinueWatchingEntry)

    @Query("DELETE FROM ContinueWatchingEntry WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}

/** Contract for user list dao. */
@Dao
interface UserListDao {
    @Query("SELECT * FROM UserList ORDER BY createdAt ASC")
    fun getListsFlow(): Flow<List<UserList>>

    @Query("SELECT * FROM UserListItem WHERE listId = :listId ORDER BY position ASC")
    fun getItemsFlow(listId: Long): Flow<List<UserListItem>>

    @Upsert
    suspend fun upsertList(value: UserList): Long

    @Upsert
    suspend fun upsertItem(value: UserListItem): Long

    @Delete
    suspend fun deleteList(value: UserList)

    @Delete
    suspend fun deleteItem(value: UserListItem)

    @Query("SELECT * FROM TitleNote WHERE tmdbId = :tmdbId AND mediaType = :mediaType ORDER BY createdAt DESC")
    fun getNotesFlow(tmdbId: Int, mediaType: String): Flow<List<TitleNote>>

    @Upsert
    suspend fun upsertNote(value: TitleNote): Long

    @Delete
    suspend fun deleteNote(value: TitleNote)
}

/** Local-only watch state: watchlist, resume positions, lists, notes. No server sync. */
@ColumnTypeConverters(DefaultConverters::class)
@Database(
    entities = [
        WatchlistEntry::class,
        ContinueWatchingEntry::class,
        UserList::class,
        UserListItem::class,
        TitleNote::class,
        AddonEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class UniflixDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun continueWatchingDao(): ContinueWatchingDao
    abstract fun userListDao(): UserListDao
    abstract fun addonDao(): AddonDao

    /** Shared constants and helpers for UniflixDatabase. */
    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations = listOf(
            object : Migration(1, 2) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Addons are new in the public app, so the table starts
                    // empty on every upgrade from the catalog-only v1.
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS AddonEntity (" +
                            "dbId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "manifestUrl TEXT NOT NULL, " +
                            "baseUrl TEXT NOT NULL, " +
                            "addonId TEXT NOT NULL, " +
                            "name TEXT NOT NULL, " +
                            "version TEXT NOT NULL, " +
                            "enabled INTEGER NOT NULL, " +
                            "priority INTEGER NOT NULL, " +
                            "configJson TEXT NOT NULL, " +
                            "lastError TEXT, " +
                            "updatedAt INTEGER NOT NULL, " +
                            "kind TEXT NOT NULL DEFAULT 'http', " +
                            "jsSource TEXT NOT NULL DEFAULT '')"
                    )
                }
            },
            object : Migration(2, 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Installable JavaScript plugins: kind + embedded source.
                    // Existing rows stay `http` URL addons.
                    connection.execSQL(
                        "ALTER TABLE AddonEntity ADD COLUMN kind TEXT NOT NULL DEFAULT 'http'",
                    )
                    connection.execSQL(
                        "ALTER TABLE AddonEntity ADD COLUMN jsSource TEXT NOT NULL DEFAULT ''",
                    )
                }
            },
        )
    }
}
