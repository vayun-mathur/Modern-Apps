package com.vayunmathur.youpipe.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

/** Immutable value describing subscription. */
@Serializable
@Entity
data class Subscription(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val channelID: String,
    val avatarURL: String
): DatabaseItem
