package com.vayunmathur.openassistant.data.database

import androidx.room.Dao
import androidx.room.Query
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.openassistant.data.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao: TrueDao<Message> {
    @Query("SELECT * FROM Message ORDER BY timestamp ASC")
    override fun getAll(): Flow<List<Message>>
}
