package com.vayunmathur.photos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import com.vayunmathur.library.util.TrueDao

@Dao
interface PhotoDao: TrueDao<Photo>

@Database(entities = [Photo::class], version = 1)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
}