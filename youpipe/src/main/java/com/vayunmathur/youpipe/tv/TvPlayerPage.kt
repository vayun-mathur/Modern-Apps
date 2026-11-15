package com.vayunmathur.youpipe.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.applyDownloadedStreams
import com.vayunmathur.youpipe.util.clearVideoError
import com.vayunmathur.youpipe.util.loadVideo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Fullscreen playback for one video - what selecting any video on TV opens. Loads the video
 * unless `videoState` already holds it, resumes from watch history, and owns next/previous:
 * "next" plays the first related video in place, "previous" goes back the way the user came
 * (the phone's rule, since SABR needs per-video extractor state there is no real queue).
 */
@Composable
fun TvPlayerPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    videoID: Long,
) {
    val videoState by ypvm.videoState.collectAsState()
    val downloadedFlow = remember(videoID) { ypvm.downloadedById(videoID) }
    val downloadedVideo by downloadedFlow.collectAsState(initial = null)
    val history by ypvm.historyVideos.collectAsState()
    TvPlayerPageLoadEffects(videoID, ypvm, downloadedFlow, downloadedVideo, videoState.error)
    val related = videoState.relatedVideos
    val canGoPrevious = backStack.backStack.size > 1
    TvPlayerPageNavigationEffects(related, canGoPrevious, backStack)
    val data = videoState.data
    when {
        videoState.error -> TvPlayerPageErrorState {
            ypvm.clearVideoError()
            TvLoadedVideo.id = null
            backStack.pop()
        }

        data == null || videoState.videoStreams.isEmpty() -> TvLoading()
        else -> {
            val resumeMs = history.firstOrNull { it.id == videoID }?.progress ?: 0L
            // Finished (or nearly) videos start over rather than resuming at the credits.
            val startMs = tvPlayerStartMs(resumeMs, data.duration)
            val contentArgs = TvPlayerPageContentArgs(
                backStack, ypvm, videoID, data, videoState, related, startMs,
            )
            TvPlayerPageContent(contentArgs)
        }
    }
}

private fun tvPlayerStartMs(resumeMs: Long, durationSeconds: Long): Long =
    resumeMs.takeIf { it < durationSeconds * MS_PER_SECOND - RESUME_TAIL_MS } ?: 0L

@Composable
private fun TvPlayerPageLoadEffects(
    videoID: Long,
    ypvm: YouPipeViewModel,
    downloadedFlow: Flow<DownloadedVideo?>,
    downloadedVideo: DownloadedVideo?,
    isError: Boolean,
) {
    LaunchedEffect(videoID) {
        if (TvLoadedVideo.id != videoID || isError) {
            TvLoadedVideo.id = videoID
            ypvm.loadVideo(videoID, downloadedFlow.first())
        }
    }
    LaunchedEffect(downloadedVideo) {
        downloadedVideo?.let { ypvm.applyDownloadedStreams(it) }
    }
}

@Composable
private fun TvPlayerPageNavigationEffects(
    related: List<VideoInfo>,
    canGoPrevious: Boolean,
    backStack: NavBackStack<TvRoute>,
) {
    LaunchedEffect(related.isNotEmpty(), canGoPrevious) {
        CastPlayback.update { it.copy(hasNext = related.isNotEmpty(), hasPrevious = canGoPrevious) }
    }
    DisposableEffect(related, canGoPrevious) {
        val handler: (Boolean) -> Unit = { next ->
            if (next) {
                related.firstOrNull()?.let { backStack.setLast(TvRoute.VideoPlayerRoute(it.videoID)) }
            } else if (canGoPrevious) {
                backStack.pop()
            }
        }
        CastPlayback.onNavigate = handler
        onDispose { if (CastPlayback.onNavigate === handler) CastPlayback.onNavigate = null }
    }
}

@Composable
private fun TvPlayerPageErrorState(onBack: () -> Unit) {
    TvEmptyState(
        title = stringResource(R.string.video_load_error),
        isError = true,
        action = {
            TvButton(
                text = stringResource(R.string.action_go_back),
                onClick = onBack,
            )
        },
    )
}

private data class TvPlayerPageContentArgs(
    val backStack: NavBackStack<TvRoute>,
    val ypvm: YouPipeViewModel,
    val videoID: Long,
    val data: VideoData,
    val videoState: YouPipeViewModel.VideoState,
    val related: List<VideoInfo>,
    val startMs: Long,
)

@Composable
private fun TvPlayerPageContent(args: TvPlayerPageContentArgs) {
    TvPlayer(
        ypvm = args.ypvm,
        videoInfo = VideoInfo(
            args.data.title, args.videoID, args.data.duration, args.data.views,
            args.data.uploadDate, args.data.thumbnailURL, args.data.author,
        ),
        videoStreams = args.videoState.videoStreams,
        audioStreams = args.videoState.audioStreams,
        subtitles = args.videoState.subtitles,
        segments = args.videoState.segments,
        startPositionMs = args.startMs,
        chrome = rememberTvPlayerChrome(
            args.backStack, args.ypvm, args.videoID, args.data, args.related,
        ),
    )
}

private const val MS_PER_SECOND = 1_000L
private const val RESUME_TAIL_MS = 15_000L
