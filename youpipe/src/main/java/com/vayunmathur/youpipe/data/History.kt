package com.vayunmathur.youpipe.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.TrueDao
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.videoURLtoID
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

@Entity
data class HistoryVideo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0, // video id
    val progress: Long, // milliseconds
    @Embedded val videoItem: VideoInfo,
    val timestamp: Instant,
    override val position: Double = 0.0
): DatabaseItem<HistoryVideo>() {
    override fun withPosition(position: Double) = copy(position = position)

    companion object {
        fun fromVideoData(videoInfo: VideoInfo, progress: Long) = HistoryVideo(videoInfo.videoID, progress, videoInfo, Clock.System.now())
    }
}

@Dao
interface HistoryVideoDao: TrueDao<HistoryVideo> {
    @Query("SELECT * FROM HistoryVideo")
    override fun getAll(): Flow<List<HistoryVideo>>
    @Query("DELETE FROM HistoryVideo")
    override suspend fun deleteAll()
}