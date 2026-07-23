package com.vayunmathur.games.hub.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    @Upsert
    suspend fun upsert(event: ActivityEventEntity)

    @Query("SELECT * FROM activity_events ORDER BY timestamp DESC LIMIT :limit")
    fun flowRecent(limit: Int = 50): Flow<List<ActivityEventEntity>>

    @Query("SELECT * FROM activity_events ORDER BY timestamp DESC")
    fun flowAll(): Flow<List<ActivityEventEntity>>

    @Query("SELECT * FROM activity_events WHERE gameId = :gameId ORDER BY timestamp DESC LIMIT :limit")
    fun flowByGame(gameId: String, limit: Int = 20): Flow<List<ActivityEventEntity>>

    @Query("DELETE FROM activity_events")
    suspend fun clearAll()

    @Query("DELETE FROM activity_events WHERE id NOT IN (SELECT id FROM activity_events ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 500)
}
