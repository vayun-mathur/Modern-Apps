package com.vayunmathur.music.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao: TrueDao<Music> {
    @Query("SELECT * FROM Music ORDER BY title ASC")
    override fun getAll(): Flow<List<Music>>

    @Query("DELETE FROM Music")
    override suspend fun deleteAll()
}

@Database(entities = [Music::class], version = 1)
abstract class MusicDatabase: RoomDatabase() {
    abstract fun musicDao(): MusicDao
}