package com.vayunmathur.web.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

// Bumped to new file name to avoid old migration crash — fresh install uses this DB.
const val DB_NAME = "web-browser-db"

/** Contract for history dao. */
@Dao
interface HistoryDao {
    @Query("SELECT * FROM HistoryEntry ORDER BY visitedAt DESC")
    fun allFlow(): Flow<List<HistoryEntry>>

    @Query(
        "SELECT * FROM HistoryEntry WHERE url LIKE '%' || :query || '%' " +
            "OR title LIKE '%' || :query || '%' ORDER BY visitedAt DESC"
    )
    fun searchFlow(query: String): Flow<List<HistoryEntry>>

    @Upsert
    suspend fun upsert(entry: HistoryEntry): Long

    @Query("DELETE FROM HistoryEntry")
    suspend fun clearAll()

    @Query("DELETE FROM HistoryEntry WHERE visitedAt < :before")
    suspend fun deleteBefore(before: Long)

    @Query("SELECT * FROM HistoryEntry ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HistoryEntry>
}

/** Contract for bookmark dao. */
@Dao
interface BookmarkDao {
    @Query("SELECT * FROM Bookmark ORDER BY createdAt DESC")
    fun allFlow(): Flow<List<Bookmark>>

    @Query("SELECT * FROM Bookmark WHERE folderId = :folderId ORDER BY createdAt DESC")
    fun byFolderFlow(folderId: Long?): Flow<List<Bookmark>>

    @Query("SELECT * FROM Bookmark WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): Bookmark?

    @Query("SELECT * FROM Bookmark WHERE url = :url LIMIT 1")
    fun byUrlFlow(url: String): Flow<Bookmark?>

    @Upsert
    suspend fun upsert(entry: Bookmark): Long

    @Delete
    suspend fun delete(entry: Bookmark)

    @Query("SELECT * FROM BookmarkFolder ORDER BY name ASC")
    fun foldersFlow(): Flow<List<BookmarkFolder>>

    @Upsert
    suspend fun upsertFolder(folder: BookmarkFolder): Long

    @Delete
    suspend fun deleteFolder(folder: BookmarkFolder)

    @Query("DELETE FROM Bookmark WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)
}

/** Contract for site permission dao. */
@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM SitePermission ORDER BY origin ASC")
    fun allFlow(): Flow<List<SitePermission>>

    @Query("SELECT * FROM SitePermission WHERE origin = :origin LIMIT 1")
    suspend fun byOrigin(origin: String): SitePermission?

    @Query("SELECT * FROM SitePermission WHERE origin = :origin LIMIT 1")
    fun byOriginFlow(origin: String): Flow<SitePermission?>

    @Upsert
    suspend fun upsert(p: SitePermission): Long

    @Delete
    suspend fun delete(p: SitePermission)

    @Query("DELETE FROM SitePermission")
    suspend fun clearAll()

    @Query("DELETE FROM SitePermission WHERE origin = :origin")
    suspend fun deleteOrigin(origin: String)
}

/** Contract for storage info dao. */
@Dao
interface StorageInfoDao {
    @Query("SELECT * FROM StorageInfo ORDER BY lastSeen DESC")
    fun allFlow(): Flow<List<StorageInfo>>

    @Query("SELECT * FROM StorageInfo WHERE origin = :origin LIMIT 1")
    suspend fun byOrigin(origin: String): StorageInfo?

    @Upsert
    suspend fun upsert(info: StorageInfo): Long

    @Delete
    suspend fun delete(info: StorageInfo)

    @Query("DELETE FROM StorageInfo WHERE origin = :origin")
    suspend fun deleteOrigin(origin: String)

    @Query("DELETE FROM StorageInfo")
    suspend fun clearAll()
}

/** Contract for download dao. */
@Dao
interface DownloadDao {
    @Query("SELECT * FROM DownloadEntry ORDER BY startedAt DESC")
    fun allFlow(): Flow<List<DownloadEntry>>

    @Upsert
    suspend fun upsert(d: DownloadEntry): Long

    @Query("DELETE FROM DownloadEntry WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM DownloadEntry")
    suspend fun clearAll()
}

/** Contract for installed site dao. */
@Dao
interface InstalledSiteDao {
    @Query("SELECT * FROM InstalledSite ORDER BY installedAt DESC")
    fun allFlow(): Flow<List<InstalledSite>>

    @Query("SELECT * FROM InstalledSite WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): InstalledSite?

    @Query("SELECT * FROM InstalledSite WHERE origin = :origin LIMIT 1")
    suspend fun byOrigin(origin: String): InstalledSite?

    @Upsert
    suspend fun upsert(site: InstalledSite)

    @Query("DELETE FROM InstalledSite WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM InstalledSite")
    suspend fun clearAll()
}

/** Contract for shield setting dao. */
@Dao
interface ShieldSettingDao {
    @Query("SELECT * FROM ShieldSetting ORDER BY host ASC")
    fun allFlow(): Flow<List<ShieldSetting>>

    /** One-shot read so the first page load already knows about site exceptions. */
    @Query("SELECT * FROM ShieldSetting")
    suspend fun all(): List<ShieldSetting>

    @Query("SELECT * FROM ShieldSetting WHERE host = :host LIMIT 1")
    suspend fun byHost(host: String): ShieldSetting?

    @Upsert
    suspend fun upsert(setting: ShieldSetting)

    @Query("DELETE FROM ShieldSetting WHERE host = :host")
    suspend fun deleteHost(host: String)

    @Query("DELETE FROM ShieldSetting")
    suspend fun clearAll()
}

@Database(
    entities = [
        HistoryEntry::class,
        Bookmark::class,
        BookmarkFolder::class,
        SitePermission::class,
        StorageInfo::class,
        DownloadEntry::class,
        InstalledSite::class,
        ShieldSetting::class,
    ],
    version = 3,
    exportSchema = false
)
/** Web Database: a room database implementation for web database. */
abstract class WebDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun sitePermissionDao(): SitePermissionDao
    abstract fun storageInfoDao(): StorageInfoDao
    abstract fun downloadDao(): DownloadDao
    abstract fun installedSiteDao(): InstalledSiteDao
    abstract fun shieldSettingDao(): ShieldSettingDao

    /** Shared constants and helpers for WebDatabase. */
    companion object : DatabaseMigrations {
        override val migrations = listOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `InstalledSite` (
                `id` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `shortName` TEXT NOT NULL,
                `iconUrl` TEXT,
                `faviconUrl` TEXT,
                `themeColor` TEXT,
                `backgroundColor` TEXT,
                `displayMode` TEXT NOT NULL,
                `startUrl` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `installedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )"""
        )
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `ShieldSetting` (
                `host` TEXT NOT NULL,
                `level` TEXT,
                `blockTrackers` INTEGER,
                `cosmeticFiltering` INTEGER,
                `fingerprintProtection` INTEGER,
                `httpsUpgrade` INTEGER,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`host`)
            )"""
        )
    }
}
