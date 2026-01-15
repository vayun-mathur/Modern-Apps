package com.vayunmathur.openassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    var title: String,
    val createdAt: Long = System.currentTimeMillis()
)
