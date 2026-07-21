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

@Dao
interface FrequentFlyerDao {
    @Query("SELECT * FROM FrequentFlyer ORDER BY airlineName")
    fun observeAll(): Flow<List<FrequentFlyer>>

    @Query("SELECT * FROM FrequentFlyer ORDER BY airlineName")
    suspend fun getAll(): List<FrequentFlyer>

    @Upsert
    suspend fun upsert(value: FrequentFlyer)

    @Query("DELETE FROM FrequentFlyer WHERE airlineIata = :airlineIata")
    suspend fun deleteById(airlineIata: String)
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM Customer ORDER BY givenName, familyName")
    fun observeAll(): Flow<List<Customer>>

    @Query("SELECT * FROM Customer WHERE id = :id")
    suspend fun byId(id: String): Customer?

    @Upsert
    suspend fun upsert(value: Customer)

    @Query("DELETE FROM Customer WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(
    entities = [RecentSearch::class, BookedTrip::class, FrequentFlyer::class, Customer::class],
    version = 4,
    exportSchema = false,
)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun bookedTripDao(): BookedTripDao
    abstract fun frequentFlyerDao(): FrequentFlyerDao
    abstract fun customerDao(): CustomerDao

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
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS FrequentFlyer (" +
                            "airlineIata TEXT NOT NULL PRIMARY KEY, " +
                            "accountNumber TEXT NOT NULL, " +
                            "airlineName TEXT NOT NULL DEFAULT '')"
                    )
                }
            },
            object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS Customer (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "email TEXT NOT NULL, " +
                            "givenName TEXT NOT NULL, " +
                            "familyName TEXT NOT NULL, " +
                            "phoneNumber TEXT NOT NULL DEFAULT '')"
                    )
                    db.execSQL("ALTER TABLE BookedTrip ADD COLUMN customerId TEXT NOT NULL DEFAULT ''")
                }
            },
        )
    }
}
