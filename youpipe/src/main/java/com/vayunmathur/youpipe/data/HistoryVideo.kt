package com.vayunmathur.youpipe.data

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlin.time.Clock
import kotlin.time.Instant

/** Immutable value describing history video. */
@Entity
data class HistoryVideo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0, // video id
    val progress: Long, // milliseconds
    @Embedded val videoItem: VideoInfo,
    val timestamp: Instant
): DatabaseItem {
    /** Shared constants and helpers for HistoryVideo. */
    companion object {
        fun fromVideoData(videoInfo: VideoInfo, progress: Long) =
            HistoryVideo(videoInfo.videoID, progress, videoInfo, Clock.System.now())
    }
}
