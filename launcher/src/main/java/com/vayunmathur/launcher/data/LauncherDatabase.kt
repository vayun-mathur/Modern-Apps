package com.vayunmathur.launcher.data

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import com.vayunmathur.launcher.domain.CONTAINER_DESKTOP
import com.vayunmathur.launcher.domain.CONTAINER_HOTSEAT
import com.vayunmathur.library.util.DatabaseMigrations
import com.vayunmathur.library.util.DefaultConverters
import kotlinx.coroutines.flow.Flow

/** Contract for launcher item dao. */
@Dao
interface LauncherItemDao {

    /**
     * The whole workspace as one flow.
     *
     * One query for everything rather than one per container: the home screen renders all
     * of them together, and Room's invalidation tracker fires per table anyway, so
     * separate flows would all re-emit on every write regardless.
     */
    @Query("SELECT * FROM launcher_items")
    fun getAllFlow(): Flow<List<LauncherItemEntity>>

    @Query("SELECT * FROM launcher_items")
    suspend fun getAll(): List<LauncherItemEntity>

    @Query("SELECT * FROM launcher_items WHERE id = :id")
    suspend fun get(id: Long): LauncherItemEntity?

    @Query("SELECT * FROM launcher_items WHERE containerId = $CONTAINER_DESKTOP")
    suspend fun getDesktopItems(): List<LauncherItemEntity>

    @Query("SELECT * FROM launcher_items WHERE containerId = $CONTAINER_HOTSEAT ORDER BY rank")
    suspend fun getHotseatItems(): List<LauncherItemEntity>

    @Query("SELECT * FROM launcher_items WHERE containerId = :folderId ORDER BY rank")
    suspend fun getFolderChildren(folderId: Long): List<LauncherItemEntity>

    /** All folder rows (for the empty-folder sweep), filtered in SQL not in memory. */
    @Query("SELECT * FROM launcher_items WHERE itemType = 'FOLDER'")
    suspend fun getFolders(): List<LauncherItemEntity>

    @Query("SELECT COUNT(*) FROM launcher_items WHERE containerId = :folderId")
    suspend fun countFolderChildren(folderId: Long): Int

    @Query("SELECT COUNT(*) FROM launcher_items")
    suspend fun count(): Int

    @Query("SELECT appWidgetId FROM launcher_items WHERE appWidgetId IS NOT NULL")
    suspend fun getUsedWidgetIds(): List<Int>

    @Upsert
    suspend fun upsert(item: LauncherItemEntity): Long

    @Upsert
    suspend fun upsertAll(items: List<LauncherItemEntity>)

    @Delete
    suspend fun delete(item: LauncherItemEntity): Int

    @Query("DELETE FROM launcher_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE launcher_items SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query(
        "UPDATE launcher_items SET containerId = :containerId, screen = :screen, " +
            "cellX = :cellX, cellY = :cellY, rank = :rank WHERE id = :id"
    )
    suspend fun move(id: Long, containerId: Long, screen: Int, cellX: Int, cellY: Int, rank: Int)

    @Query("UPDATE launcher_items SET cellX = :cellX, cellY = :cellY, spanX = :spanX, spanY = :spanY WHERE id = :id")
    suspend fun resizeTo(id: Long, cellX: Int, cellY: Int, spanX: Int, spanY: Int)

    @Query("UPDATE launcher_items SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String?)

    @Query("UPDATE launcher_items SET rank = :rank WHERE id = :id")
    suspend fun setRank(id: Long, rank: Int)
}

/** Launcher Database: a room database implementation for launcher database. */
@ColumnTypeConverters(DefaultConverters::class, LauncherConverters::class)
@Database(entities = [LauncherItemEntity::class], version = 1, exportSchema = true)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun itemDao(): LauncherItemDao

    /** Shared constants and helpers for LauncherDatabase. */
    companion object : DatabaseMigrations {
        override val migrations: List<Migration> = emptyList()
    }
}
