package com.vayunmathur.findfamily.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.vayunmathur.library.util.DefaultConverters
import kotlinx.coroutines.flow.Flow


@Dao
interface LocationValueDao {
    @Query("SELECT * FROM LocationValue WHERE (userid, timestamp) IN ( SELECT userid, MAX(timestamp) FROM LocationValue GROUP BY userid )")
    fun getLatest(): Flow<List<LocationValue>>

    @Query("SELECT * FROM LocationValue WHERE userid = :userid")
    fun getByUseridFlow(userid: Long): Flow<List<LocationValue>>

    @Query("DELETE FROM LocationValue WHERE timestamp < :cutoffEpochSeconds")
    suspend fun deleteOlderThan(cutoffEpochSeconds: Long)

    @Upsert
    suspend fun upsert(value: LocationValue): Long

    @Upsert
    suspend fun upsertAll(values: List<LocationValue>)
}

@Dao
interface WaypointDao {
    @Query("SELECT * FROM Waypoint")
    fun getAllFlow(): Flow<List<Waypoint>>

    @Query("SELECT * FROM Waypoint WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Waypoint?>

    @Query("SELECT * FROM Waypoint")
    suspend fun getAll(): List<Waypoint>

    @Query("SELECT * FROM Waypoint WHERE id = :id")
    suspend fun get(id: Long): Waypoint

    @Upsert
    suspend fun upsert(value: Waypoint): Long

    @Delete
    suspend fun delete(value: Waypoint): Int
}

@Dao
interface UserDao {
    @Query("SELECT * FROM User")
    fun getAllFlow(): Flow<List<User>>

    @Query("SELECT * FROM User WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<User?>

    @Query("SELECT * FROM User")
    suspend fun getAll(): List<User>

    @Upsert
    suspend fun upsert(value: User): Long

    @Upsert
    suspend fun upsertAll(values: List<User>)

    @Delete
    suspend fun delete(value: User): Int
}

@Dao
interface TemporaryLinkDao {
    @Query("SELECT * FROM TemporaryLink")
    fun getAllFlow(): Flow<List<TemporaryLink>>

    @Query("SELECT * FROM TemporaryLink WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<TemporaryLink?>

    @Query("SELECT * FROM TemporaryLink")
    suspend fun getAll(): List<TemporaryLink>

    @Upsert
    suspend fun upsert(value: TemporaryLink): Long

    @Delete
    suspend fun delete(value: TemporaryLink): Int
}

@Database(entities = [User::class, Waypoint::class, LocationValue::class, TemporaryLink::class], version = 5)
@TypeConverters(DefaultConverters::class)
abstract class FFDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun waypointDao(): WaypointDao
    abstract fun locationValueDao(): LocationValueDao
    abstract fun temporaryLinkDao(): TemporaryLinkDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<androidx.room.migration.Migration> = listOf(
            androidx.room.migration.Migration(1, 2) {
                it.execSQL("CREATE INDEX IF NOT EXISTS index_LocationValue_timestamp ON LocationValue (timestamp)")
            },
            androidx.room.migration.Migration(2, 3) {
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_LocationValue_userid_timestamp` " +
                        "ON `LocationValue` (`userid`, `timestamp`)"
                )
            },
            androidx.room.migration.Migration(3, 4) {
                it.execSQL("ALTER TABLE `User` ADD COLUMN `lastWaypointId` INTEGER")
            },
            androidx.room.migration.Migration(4, 5) {
                it.execSQL("ALTER TABLE `User` ADD COLUMN `platform` TEXT")
            }
        )
    }
}
