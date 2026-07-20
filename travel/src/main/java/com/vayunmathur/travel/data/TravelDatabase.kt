package com.vayunmathur.travel.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "travel-db"

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM RecentSearch ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<RecentSearch>>

    @Insert
    suspend fun insert(value: RecentSearch)

    @Delete
    suspend fun delete(value: RecentSearch)

    @Query("DELETE FROM RecentSearch")
    suspend fun clear()

    /**
     * Drop rows beyond the newest [keep], so history doesn't grow unbounded.
     * Called after each insert.
     */
    @Query(
        "DELETE FROM RecentSearch WHERE id NOT IN " +
            "(SELECT id FROM RecentSearch ORDER BY createdAt DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int = 20)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM Favorite ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Favorite>>

    @Upsert
    suspend fun upsert(value: Favorite)

    @Query("DELETE FROM Favorite WHERE bookingUrl = :bookingUrl")
    suspend fun deleteByUrl(bookingUrl: String)
}

@Database(
    entities = [RecentSearch::class, Favorite::class],
    version = 1,
    exportSchema = false,
)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun favoriteDao(): FavoriteDao

    companion object : DatabaseMigrations {
        override val migrations = emptyList<androidx.room.migration.Migration>()
    }
}
