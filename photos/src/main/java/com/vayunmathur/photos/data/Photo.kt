package com.vayunmathur.photos.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
data class VideoData(val duration: Long)

@Serializable
@Entity(indices = [Index(value = ["date"])])
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
) : DatabaseItem