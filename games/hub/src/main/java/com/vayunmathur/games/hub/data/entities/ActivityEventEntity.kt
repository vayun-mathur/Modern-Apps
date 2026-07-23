package com.vayunmathur.games.hub.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_events")
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val gameId: String? = null,
    val title: String,
    val description: String? = null,
    val payloadJson: String? = null
) {
    companion object {
        const val TYPE_ACHIEVEMENT_UNLOCKED = "ACHIEVEMENT_UNLOCKED"
        const val TYPE_SESSION_COMPLETED = "SESSION_COMPLETED"
        const val TYPE_LEVEL_UP = "LEVEL_UP"
        const val TYPE_GAME_REGISTERED = "GAME_REGISTERED"
        const val TYPE_FIRST_PLAY = "FIRST_PLAY"
        const val TYPE_GAME_LAUNCHED = "GAME_LAUNCHED"
    }
}
