package com.vayunmathur.games.hub.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hub_games")
data class HubGameEntity(
    @PrimaryKey val gameId: String,
    val packageName: String,
    val displayName: String,
    val description: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val registeredAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
    val totalPlaytimeMs: Long = 0L,
    val totalSessions: Int = 0
)
