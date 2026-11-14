package com.vayunmathur.music.data
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

/** Immutable value describing music. */
@Serializable
@Entity
data class Music(
    @PrimaryKey(autoGenerate = true) override val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val uri: String,
    val duration: Long,
    val trackNumber: Int,
    val year: Int,
    val discNumber: Int = 1
): DatabaseItem
