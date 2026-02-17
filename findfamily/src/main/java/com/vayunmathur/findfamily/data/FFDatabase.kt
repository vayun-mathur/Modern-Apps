package com.vayunmathur.findfamily.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.TrueDao


@Dao
interface LocationValueDao: TrueDao<LocationValue>

@Dao
interface WaypointDao: TrueDao<Waypoint>

@Dao
interface UserDao: TrueDao<User>

@Dao
interface TemporaryLinkDao: TrueDao<TemporaryLink>

@Database(entities = [User::class, Waypoint::class, LocationValue::class, TemporaryLink::class], version = 1)
@TypeConverters(DefaultConverters::class)
abstract class FFDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun waypointDao(): WaypointDao
    abstract fun locationValueDao(): LocationValueDao
    abstract fun temporaryLinkDao(): TemporaryLinkDao
}
