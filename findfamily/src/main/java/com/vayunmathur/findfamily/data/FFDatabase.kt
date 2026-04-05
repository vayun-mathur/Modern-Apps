package com.vayunmathur.findfamily.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


@Dao
interface LocationValueDao: TrueDao<LocationValue> {
    @Query("SELECT * FROM LocationValue WHERE (userid, timestamp) IN ( SELECT userid, MAX(timestamp) FROM LocationValue GROUP BY userid )")
    fun getLatest(): Flow<List<LocationValue>>
}

fun DatabaseViewModel.getLatestMap(): Flow<Map<Long, LocationValue>> {
    return (this.daos[LocationValue::class] as LocationValueDao).getLatest().map { list -> list.associate { it.userid to it } }
}

@Dao
interface WaypointDao: TrueDao<Waypoint>

@Dao
interface UserDao: TrueDao<User>

@Dao
interface TemporaryLinkDao: TrueDao<TemporaryLink>

@Database(entities = [User::class, Waypoint::class, LocationValue::class, TemporaryLink::class], version = 3)
@TypeConverters(DefaultConverters::class)
abstract class FFDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun waypointDao(): WaypointDao
    abstract fun locationValueDao(): LocationValueDao
    abstract fun temporaryLinkDao(): TemporaryLinkDao
}
