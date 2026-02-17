package com.vayunmathur.music.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import com.vayunmathur.library.util.TrueDao

@Dao
interface MusicDao: TrueDao<Music>

@Database(entities = [Music::class], version = 1)
abstract class MusicDatabase: RoomDatabase() {
    abstract fun musicDao(): MusicDao
}