package com.vayunmathur.music.data
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Playlist(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String
): DatabaseItem
