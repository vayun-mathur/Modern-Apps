package com.vayunmathur.youpipe.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlin.time.Clock
import kotlin.time.Instant

@Entity
data class HistoryVideo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0, // video id
    val progress: Long, // milliseconds
    @Embedded val videoItem: VideoInfo,
    val timestamp: Instant
): DatabaseItem {
    companion object {
        fun fromVideoData(videoInfo: VideoInfo, progress: Long) = HistoryVideo(videoInfo.videoID, progress, videoInfo, Clock.System.now())
    }
}