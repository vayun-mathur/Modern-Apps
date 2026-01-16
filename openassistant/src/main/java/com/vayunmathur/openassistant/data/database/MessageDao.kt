package com.vayunmathur.openassistant.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.openassistant.data.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao: TrueDao<Message> {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    override fun getAll(): Flow<List<Message>>

    @Upsert
    override suspend fun upsert(value: Message): Long

    @Delete
    override suspend fun delete(value: Message): Int
}
