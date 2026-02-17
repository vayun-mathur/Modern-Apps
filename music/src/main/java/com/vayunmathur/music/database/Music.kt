package com.vayunmathur.music.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Music(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val uri: String
): DatabaseItem