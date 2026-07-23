package com.vayunmathur.games.hub.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "play_sessions", indices = [Index(value = ["sessionId"], unique = true)])
data class PlaySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val durationMs: Long? = null
)
