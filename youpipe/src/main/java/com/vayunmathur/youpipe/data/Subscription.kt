package com.vayunmathur.youpipe.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.youpipe.ui.ChannelInfo
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Subscription(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val channelID: String,
    val avatarURL: String
): DatabaseItem