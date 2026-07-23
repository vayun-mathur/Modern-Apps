package com.vayunmathur.games.hub.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Upsert
    suspend fun upsert(session: PlaySessionEntity)

    @Query("SELECT * FROM play_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySessionId(sessionId: String): PlaySessionEntity?

    @Query("SELECT * FROM play_sessions WHERE gameId = :gameId ORDER BY startTime DESC")
    fun flowByGame(gameId: String): Flow<List<PlaySessionEntity>>

    @Query("SELECT * FROM play_sessions ORDER BY startTime DESC")
    fun flowAll(): Flow<List<PlaySessionEntity>>

    @Query("SELECT * FROM play_sessions ORDER BY startTime DESC LIMIT :limit")
    fun flowRecent(limit: Int = 50): Flow<List<PlaySessionEntity>>

    @Query("UPDATE play_sessions SET endTime = :endTime, durationMs = :durationMs WHERE sessionId = :sessionId")
    suspend fun endSession(sessionId: String, endTime: Long, durationMs: Long)

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM play_sessions WHERE endTime IS NOT NULL")
    fun flowTotalPlaytimeMs(): Flow<Long>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM play_sessions WHERE gameId = :gameId AND endTime IS NOT NULL")
    fun flowPlaytimeByGame(gameId: String): Flow<Long>

    @Query("SELECT COUNT(*) FROM play_sessions")
    fun flowTotalCount(): Flow<Int>

    @Query("DELETE FROM play_sessions")
    suspend fun clearAll()
}
