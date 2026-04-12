package com.vayunmathur.photos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.vayunmathur.library.util.TrueDao

@Dao
interface PhotoDao: TrueDao<Photo>

@Database(entities = [Photo::class], version = 2)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
}

val MIGRATION_1_2 = Migration(1, 2) {
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_Photo_date` ON `Photo` (`date`)")
}