package com.vayunmathur.games.hub.data.entities

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Immutable value describing play session entity. */
@Entity(tableName = "play_sessions", indices = [Index(value = ["sessionId"], unique = true)])
data class PlaySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gameId: String,
    val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val durationMs: Long? = null
)
