package com.vayunmathur.games.hub.data.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import kotlinx.coroutines.flow.Flow

/** Contract for streak dao. */
@Dao
interface StreakDao {

    @Upsert
    suspend fun upsert(streak: DailyStreakEntity)

    @Query("SELECT * FROM daily_streaks WHERE gameId = :gameId LIMIT 1")
    suspend fun getByGame(gameId: String): DailyStreakEntity?

    @Query("SELECT * FROM daily_streaks WHERE gameId = :gameId LIMIT 1")
    fun flowByGame(gameId: String): Flow<DailyStreakEntity?>

    @Query("SELECT * FROM daily_streaks")
    fun flowAll(): Flow<List<DailyStreakEntity>>

    @Query("DELETE FROM daily_streaks")
    suspend fun clearAll()
}
