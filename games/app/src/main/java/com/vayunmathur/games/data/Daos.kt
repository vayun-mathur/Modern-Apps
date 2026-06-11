package com.vayunmathur.games.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AchievementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AchievementEntity): Long

    @Query("UPDATE achievements SET unlocked = :unlocked, unlockedAt = :unlockedAt WHERE gameId = :gameId AND achievementId = :achievementId")
    suspend fun unlock(gameId: String, achievementId: String, unlocked: Boolean, unlockedAt: Long): Int

    @Query("SELECT * FROM achievements WHERE gameId = :gameId")
    suspend fun getByGame(gameId: String): List<AchievementEntity>

    @Query("SELECT * FROM achievements WHERE gameId = :gameId AND achievementId = :achievementId LIMIT 1")
    suspend fun get(gameId: String, achievementId: String): AchievementEntity?

    @Query("SELECT * FROM achievements")
    suspend fun getAll(): List<AchievementEntity>

    @Query("DELETE FROM achievements WHERE gameId = :gameId AND achievementId = :achievementId")
    suspend fun delete(gameId: String, achievementId: String): Int

    @Query("DELETE FROM achievements WHERE gameId = :gameId")
    suspend fun deleteByGame(gameId: String): Int
}

@Dao
interface ScoreDao {
    @Insert
    suspend fun insert(entity: ScoreEntity): Long

    @Query("SELECT * FROM scores WHERE gameId = :gameId ORDER BY score DESC")
    suspend fun getByGame(gameId: String): List<ScoreEntity>

    @Query("SELECT * FROM scores WHERE gameId = :gameId AND category = :category ORDER BY score DESC")
    suspend fun getByGameAndCategory(gameId: String, category: String): List<ScoreEntity>

    @Query("SELECT * FROM scores ORDER BY score DESC")
    suspend fun getAll(): List<ScoreEntity>

    @Query("DELETE FROM scores WHERE gameId = :gameId")
    suspend fun deleteByGame(gameId: String): Int
}
