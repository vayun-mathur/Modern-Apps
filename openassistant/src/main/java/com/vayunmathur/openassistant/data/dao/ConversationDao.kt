package com.vayunmathur.openassistant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.openassistant.data.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao: TrueDao<Conversation> {
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    override fun getAll(): Flow<List<Conversation>>

    @Upsert
    override suspend fun upsert(value: Conversation): Long

    @Delete
    override suspend fun delete(value: Conversation): Int
}
