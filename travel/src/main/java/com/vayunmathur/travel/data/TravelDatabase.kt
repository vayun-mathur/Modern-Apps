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
interface BookedTripDao {
    @Query("SELECT * FROM BookedTrip ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookedTrip>>

    @Query("SELECT * FROM BookedTrip WHERE orderId = :orderId")
    suspend fun byId(orderId: String): BookedTrip?

    @Upsert
    suspend fun upsert(value: BookedTrip)

    @Query("DELETE FROM BookedTrip WHERE orderId = :orderId")
    suspend fun deleteById(orderId: String)
}

@Database(
    entities = [RecentSearch::class, BookedTrip::class],
    version = 2,
    exportSchema = false,
)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun bookedTripDao(): BookedTripDao

    companion object : DatabaseMigrations {
        override val migrations = listOf(
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE BookedTrip ADD COLUMN type TEXT NOT NULL DEFAULT 'flight'")
                    db.execSQL("ALTER TABLE BookedTrip ADD COLUMN awaitingPayment INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE BookedTrip ADD COLUMN paymentRequiredBy TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE BookedTrip ADD COLUMN remoteSyncedAt INTEGER NOT NULL DEFAULT 0")
                }
            },
        )
    }
}
