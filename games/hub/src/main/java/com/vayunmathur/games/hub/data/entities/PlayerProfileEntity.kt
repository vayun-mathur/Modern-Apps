package com.vayunmathur.games.hub.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "player_profile")
data class PlayerProfileEntity(
    @PrimaryKey val id: Int = 0,
    val displayName: String = "Player",
    val avatarSymbol: String? = null,
    val avatarUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val title: String? = null
)
