package com.vayunmathur.games.hub.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Immutable value describing player profile entity. */
@Entity(tableName = "player_profile")
data class PlayerProfileEntity(
    @PrimaryKey val id: Int = 0,
    val displayName: String = "Player",
    val avatarSymbol: String? = null,
    val avatarUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val title: String? = null
)
