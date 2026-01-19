package com.vayunmathur.photos.data

import androidx.room.Dao
import androidx.room.Query
import com.vayunmathur.library.util.TrueDao
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao: TrueDao<Photo> {
    @Query("SELECT * FROM Photo ORDER BY position")
    override fun getAll(): Flow<List<Photo>>
}