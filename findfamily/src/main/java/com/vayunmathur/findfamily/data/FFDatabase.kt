package com.vayunmathur.findfamily.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters

@Database(entities = [User::class, Waypoint::class, LocationValue::class], version = 1)
@TypeConverters(DefaultConverters::class)
abstract class FFDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun waypointDao(): WaypointDao
    abstract fun locationValueDao(): LocationValueDao
}
