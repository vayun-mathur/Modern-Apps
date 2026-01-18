package com.vayunmathur.openassistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
    var title: String,
    val createdAt: Long = System.currentTimeMillis(),
    override val position: Double = 0.0
): DatabaseItem<Conversation>() {
    override fun withPosition(position: Double) = copy(position = position)
}
