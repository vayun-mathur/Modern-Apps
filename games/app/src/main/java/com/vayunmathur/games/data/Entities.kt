package com.vayunmathur.games.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val achievementId: String,
    val name: String,
    val description: String,
    val iconResName: String? = null,
    val unlocked: Boolean = false,
    val unlockedAt: Long? = null
)

@Entity(tableName = "scores")
data class ScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val category: String,
    val score: Long,
    val timestamp: Long
)
