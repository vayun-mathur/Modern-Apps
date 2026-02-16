package com.vayunmathur.photos.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
data class VideoData(val duration: Long)

@Serializable
@Entity
data class Photo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val uri: String,
    val date: Long,
    val width: Int,
    val height: Int,
    val exifSet: Boolean,
    val lat: Double?,
    val long: Double?,
    @Embedded
    val videoData: VideoData?,
    override val position: Double = 0.0
) : DatabaseItem<Photo>() {
    override fun withPosition(position: Double): Photo = copy(position = position)
}