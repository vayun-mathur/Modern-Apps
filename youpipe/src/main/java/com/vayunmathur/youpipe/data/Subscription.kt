package com.vayunmathur.youpipe.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Subscription(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val url: String,
    val avatarURL: String,
    override val position: Double = 0.0
): DatabaseItem<Subscription>() {
    override fun withPosition(position: Double) = copy(position = position)
}