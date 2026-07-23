package com.vayunmathur.games.hub.data.entities

import androidx.room.Entity

@Entity(tableName = "achievement_progress", primaryKeys = ["gameId", "achievementId"])
data class AchievementProgressEntity(
    val gameId: String,
    val achievementId: String,
    val progress: Int = 0,
    val isUnlocked: Boolean = false,
    val unlockedAt: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
