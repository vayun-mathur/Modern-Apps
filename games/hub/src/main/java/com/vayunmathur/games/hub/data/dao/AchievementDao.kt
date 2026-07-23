package com.vayunmathur.games.hub.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import kotlinx.coroutines.flow.Flow

data class AchievementWithProgress(
    val gameId: String,
    val achievementId: String,
    val name: String,
    val description: String,
    val xpReward: Int,
    val targetProgress: Int,
    val isSecret: Boolean,
    val tier: String,
    val iconResName: String?,
    val progress: Int,
    val isUnlocked: Boolean,
    val unlockedAt: Long?
)

@Dao
interface AchievementDao {

    @Upsert
    suspend fun upsertDef(def: AchievementDefEntity)

    @Upsert
    suspend fun upsertDefs(defs: List<AchievementDefEntity>)

    @Query("SELECT * FROM achievement_defs WHERE gameId = :gameId ORDER BY name ASC")
    fun flowDefsByGame(gameId: String): Flow<List<AchievementDefEntity>>

    @Query("SELECT * FROM achievement_defs ORDER BY gameId ASC, name ASC")
    fun flowAllDefs(): Flow<List<AchievementDefEntity>>

    @Query("SELECT * FROM achievement_defs WHERE gameId = :gameId AND achievementId = :achievementId LIMIT 1")
    suspend fun getDef(gameId: String, achievementId: String): AchievementDefEntity?

    @Query("SELECT COUNT(*) FROM achievement_defs")
    fun flowTotalDefsCount(): Flow<Int>

    @Upsert
    suspend fun upsertProgress(progress: AchievementProgressEntity)

    @Upsert
    suspend fun upsertProgresses(progresses: List<AchievementProgressEntity>)

    @Query("SELECT * FROM achievement_progress WHERE gameId = :gameId AND achievementId = :achievementId LIMIT 1")
    suspend fun getProgress(gameId: String, achievementId: String): AchievementProgressEntity?

    @Query("SELECT * FROM achievement_progress WHERE gameId = :gameId")
    fun flowProgressByGame(gameId: String): Flow<List<AchievementProgressEntity>>

    @Query("SELECT * FROM achievement_progress ORDER BY lastUpdated DESC")
    fun flowAllProgress(): Flow<List<AchievementProgressEntity>>

    @Query("SELECT * FROM achievement_progress WHERE isUnlocked = 1 ORDER BY unlockedAt DESC")
    fun flowUnlockedProgress(): Flow<List<AchievementProgressEntity>>

    @Query("""
        SELECT
            d.gameId, d.achievementId, d.name, d.description, d.xpReward,
            d.targetProgress, d.isSecret, d.tier, d.iconResName,
            COALESCE(p.progress, 0) as progress,
            COALESCE(p.isUnlocked, 0) as isUnlocked,
            p.unlockedAt as unlockedAt
        FROM achievement_defs d
        LEFT JOIN achievement_progress p
            ON p.gameId = d.gameId AND p.achievementId = d.achievementId
        ORDER BY d.gameId ASC, p.isUnlocked DESC, d.name ASC
    """)
    fun flowAllWithProgress(): Flow<List<AchievementWithProgress>>

    @Query("""
        SELECT
            d.gameId, d.achievementId, d.name, d.description, d.xpReward,
            d.targetProgress, d.isSecret, d.tier, d.iconResName,
            COALESCE(p.progress, 0) as progress,
            COALESCE(p.isUnlocked, 0) as isUnlocked,
            p.unlockedAt as unlockedAt
        FROM achievement_defs d
        LEFT JOIN achievement_progress p
            ON p.gameId = d.gameId AND p.achievementId = d.achievementId
        WHERE d.gameId = :gameId
        ORDER BY p.isUnlocked DESC, d.name ASC
    """)
    fun flowByGameWithProgress(gameId: String): Flow<List<AchievementWithProgress>>

    @Query("""
        SELECT
            d.gameId, d.achievementId, d.name, d.description, d.xpReward,
            d.targetProgress, d.isSecret, d.tier, d.iconResName,
            COALESCE(p.progress, 0) as progress,
            COALESCE(p.isUnlocked, 0) as isUnlocked,
            p.unlockedAt as unlockedAt
        FROM achievement_defs d
        LEFT JOIN achievement_progress p
            ON p.gameId = d.gameId AND p.achievementId = d.achievementId
        WHERE p.isUnlocked = 1
        ORDER BY p.unlockedAt DESC
    """)
    fun flowUnlockedWithProgress(): Flow<List<AchievementWithProgress>>

    @Query("""
        SELECT COALESCE(SUM(d.xpReward), 0)
        FROM achievement_defs d
        INNER JOIN achievement_progress p
            ON p.gameId = d.gameId AND p.achievementId = d.achievementId
        WHERE p.isUnlocked = 1
    """)
    fun flowTotalXp(): Flow<Int>

    @Query("DELETE FROM achievement_defs")
    suspend fun clearDefs()

    @Query("DELETE FROM achievement_progress")
    suspend fun clearProgress()
}
