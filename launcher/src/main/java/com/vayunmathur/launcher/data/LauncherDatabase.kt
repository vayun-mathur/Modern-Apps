package com.vayunmathur.launcher.data

import androidx.room.*
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.coroutines.flow.Flow

enum class HomeItemType { APP, FOLDER }

@Entity(tableName = "home_screen_items")
data class HomeScreenItem(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val pageIndex: Int,
    val row: Int,
    val col: Int,
    val packageName: String,
    val activityName: String = "",
    val type: HomeItemType = HomeItemType.APP,
    val folderId: Long? = null
) : DatabaseItem

@Entity(tableName = "dock_items")
data class DockItem(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val position: Int,
    val packageName: String,
    val activityName: String = ""
) : DatabaseItem

@Entity(tableName = "widget_items")
data class WidgetItem(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val pageIndex: Int,
    val row: Int,
    val col: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val appWidgetId: Int = -1
) : DatabaseItem

@Entity(tableName = "folders")
data class FolderInfo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val title: String = "Folder",
    val pageIndex: Int = 0,
    val row: Int = 0,
    val col: Int = 0
) : DatabaseItem

@Dao
interface HomeScreenDao {
    @Query("SELECT * FROM home_screen_items WHERE pageIndex = :page AND folderId IS NULL")
    fun getItemsForPage(page: Int): Flow<List<HomeScreenItem>>

    @Query("SELECT * FROM home_screen_items WHERE folderId = :folderId")
    fun getItemsInFolder(folderId: Long): Flow<List<HomeScreenItem>>

    @Query("SELECT * FROM home_screen_items")
    fun getAllItems(): Flow<List<HomeScreenItem>>

    @Upsert
    suspend fun upsert(item: HomeScreenItem): Long

    @Delete
    suspend fun delete(item: HomeScreenItem)

    @Query("DELETE FROM home_screen_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM home_screen_items")
    suspend fun count(): Int
}

@Dao
interface DockDao {
    @Query("SELECT * FROM dock_items ORDER BY position ASC")
    fun getAll(): Flow<List<DockItem>>

    @Upsert
    suspend fun upsert(item: DockItem): Long

    @Delete
    suspend fun delete(item: DockItem)

    @Query("DELETE FROM dock_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM dock_items")
    suspend fun count(): Int
}

@Dao
interface WidgetDao {
    @Query("SELECT * FROM widget_items WHERE pageIndex = :page")
    fun getWidgetsForPage(page: Int): Flow<List<WidgetItem>>

    @Query("SELECT * FROM widget_items")
    fun getAll(): Flow<List<WidgetItem>>

    @Upsert
    suspend fun upsert(item: WidgetItem): Long

    @Delete
    suspend fun delete(item: WidgetItem)

    @Query("DELETE FROM widget_items WHERE appWidgetId = :widgetId")
    suspend fun deleteByWidgetId(widgetId: Int)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders")
    fun getAll(): Flow<List<FolderInfo>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderInfo?

    @Upsert
    suspend fun upsert(folder: FolderInfo): Long

    @Delete
    suspend fun delete(folder: FolderInfo)
}

class HomeItemTypeConverter {
    @TypeConverter
    fun fromType(type: HomeItemType): String = type.name

    @TypeConverter
    fun toType(value: String): HomeItemType = HomeItemType.valueOf(value)
}

@Database(
    entities = [HomeScreenItem::class, DockItem::class, WidgetItem::class, FolderInfo::class],
    version = 1
)
@TypeConverters(HomeItemTypeConverter::class)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun homeScreenDao(): HomeScreenDao
    abstract fun dockDao(): DockDao
    abstract fun widgetDao(): WidgetDao
    abstract fun folderDao(): FolderDao
}
