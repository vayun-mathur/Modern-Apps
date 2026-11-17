package com.vayunmathur.youpipe.platform

import android.content.Context
import androidx.work.WorkManager
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Immutable value describing download status. */
data class DownloadStatus(
    val videoInfo: VideoInfo,
    val progress: Double
)

/** Singleton holding download manager state and operations. */
object DownloadManager {
    private val _activeDownloads = MutableStateFlow<Map<Long, DownloadStatus>>(emptyMap())
    val activeDownloads: StateFlow<Map<Long, DownloadStatus>> = _activeDownloads.asStateFlow()

    fun enqueueDownload(context: Context, videoInfo: VideoInfo, videoUrl: String, audioUrl: String?) {
        _activeDownloads.value += videoInfo.videoID to DownloadStatus(videoInfo, 0.0)
        DownloadWorker.enqueue(context, videoInfo, videoUrl, audioUrl)
    }

    fun updateProgress(videoID: Long, progress: Double) {
        _activeDownloads.update { current ->
            current[videoID]?.let {
                current + (videoID to it.copy(progress = progress))
            } ?: current
        }
    }

    fun finishDownload(videoID: Long) {
        _activeDownloads.update { it - videoID }
    }

    fun cancelDownload(context: Context, videoID: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("download_$videoID")
        _activeDownloads.update { it - videoID }
    }
}
