package com.vayunmathur.travel.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "travel-db"

/** Contract for recent search dao. */
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

/** Contract for booked trip dao. */
@Dao
interface BookedTripDao {
    @Query("SELECT * FROM BookedTrip ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookedTrip>>

    @Query("SELECT * FROM BookedTrip ORDER BY createdAt DESC")
    suspend fun getAll(): List<BookedTrip>

    @Query("SELECT * FROM BookedTrip WHERE orderId = :orderId")
    suspend fun byId(orderId: String): BookedTrip?

    @Upsert
    suspend fun upsert(value: BookedTrip)

    @Query("DELETE FROM BookedTrip WHERE orderId = :orderId")
    suspend fun deleteById(orderId: String)
}

/** Contract for frequent flyer dao. */
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

/** Contract for customer dao. */
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
    version = 5,
    exportSchema = false,
)
/** Travel Database: a room database implementation for travel database. */
abstract class TravelDatabase : RoomDatabase() {
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun bookedTripDao(): BookedTripDao
    abstract fun frequentFlyerDao(): FrequentFlyerDao
    abstract fun customerDao(): CustomerDao

    /** Shared constants and helpers for TravelDatabase. */
    companion object : DatabaseMigrations {
        override val migrations = listOf(
            object : androidx.room3.migration.Migration(1, 2) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN type TEXT NOT NULL DEFAULT 'flight'")
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN awaitingPayment INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN paymentRequiredBy TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN remoteSyncedAt INTEGER NOT NULL DEFAULT 0")
                }
            },
            object : androidx.room3.migration.Migration(2, 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS FrequentFlyer (" +
                            "airlineIata TEXT NOT NULL PRIMARY KEY, " +
                            "accountNumber TEXT NOT NULL, " +
                            "airlineName TEXT NOT NULL DEFAULT '')"
                    )
                }
            },
            object : androidx.room3.migration.Migration(3, 4) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS Customer (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "email TEXT NOT NULL, " +
                            "givenName TEXT NOT NULL, " +
                            "familyName TEXT NOT NULL, " +
                            "phoneNumber TEXT NOT NULL DEFAULT '')"
                    )
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN customerId TEXT NOT NULL DEFAULT ''")
                }
            },
            object : androidx.room3.migration.Migration(4, 5) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN tripId TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN checkoutExpiresAt TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN lastName TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE BookedTrip ADD COLUMN failureMessage TEXT NOT NULL DEFAULT ''")
                }
            },
        )
    }
}
