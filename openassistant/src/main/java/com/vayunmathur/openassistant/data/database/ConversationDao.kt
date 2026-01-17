package com.vayunmathur.openassistant.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.openassistant.data.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao: TrueDao<Conversation> {
    @Query("SELECT * FROM conversations ORDER BY createdAt DESC")
    override fun getAll(): Flow<List<Conversation>>
}
