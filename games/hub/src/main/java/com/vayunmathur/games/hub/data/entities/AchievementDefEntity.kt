package com.vayunmathur.games.hub.data.entities

import androidx.room.Entity

@Entity(tableName = "achievement_defs", primaryKeys = ["gameId", "achievementId"])
data class AchievementDefEntity(
    val gameId: String,
    val achievementId: String,
    val name: String,
    val description: String,
    val xpReward: Int = 25,
    val targetProgress: Int = 1,
    val isSecret: Boolean = false,
    val tier: String = "BRONZE",
    val iconResName: String? = null
)
