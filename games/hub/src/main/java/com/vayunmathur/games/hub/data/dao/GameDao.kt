package com.vayunmathur.games.hub.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM hub_games ORDER BY lastPlayedAt DESC, displayName ASC")
    fun flowAll(): Flow<List<HubGameEntity>>

    @Query("SELECT * FROM hub_games WHERE gameId = :gameId LIMIT 1")
    fun flowById(gameId: String): Flow<HubGameEntity?>

    @Query("SELECT * FROM hub_games WHERE gameId = :gameId LIMIT 1")
    suspend fun getById(gameId: String): HubGameEntity?

    @Query("SELECT * FROM hub_games ORDER BY lastPlayedAt DESC")
    suspend fun getAll(): List<HubGameEntity>

    @Upsert
    suspend fun upsert(game: HubGameEntity)

    @Query("UPDATE hub_games SET lastSeenAt = :timestamp WHERE gameId = :gameId")
    suspend fun touchLastSeen(gameId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE hub_games SET lastPlayedAt = :timestamp, totalSessions = totalSessions + 1 WHERE gameId = :gameId")
    suspend fun markPlayed(gameId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE hub_games SET totalPlaytimeMs = totalPlaytimeMs + :increment WHERE gameId = :gameId")
    suspend fun addPlaytime(gameId: String, increment: Long)

    @Query("DELETE FROM hub_games WHERE gameId = :gameId")
    suspend fun delete(gameId: String)

    @Query("DELETE FROM hub_games")
    suspend fun clearAll()

    @Query("SELECT COALESCE(SUM(totalPlaytimeMs), 0) FROM hub_games")
    fun flowTotalPlaytimeMs(): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalSessions), 0) FROM hub_games")
    fun flowTotalSessions(): Flow<Int>
}
