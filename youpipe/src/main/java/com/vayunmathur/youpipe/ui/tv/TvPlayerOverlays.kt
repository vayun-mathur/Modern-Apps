package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import androidx.media3.session.MediaController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.network.SponsorSegment

/**
 * Subtitle cues, buffering spinner, the YouTube-style control overlay, option sheets - everything
 * drawn over the player surface. Split from [TvPlayer] (FileLength): presentation only.
 */
@Composable
internal fun BoxScope.TvPlayerOverlays(
    ypvm: YouPipeViewModel, videoInfo: VideoInfo, videoStreams: List<VideoStream>,
    subtitles: List<SubtitleTrack>, segments: List<VideoChapter>,
    transport: CastPlayback.Transport, cues: List<Cue>, overlayVisible: Boolean,
    controller: MediaController?, languageEntries: List<Pair<String, String>>, language: String,
    onLanguage: (String) -> Unit, selectedSubtitle: SubtitleTrack?, onSubtitle: (SubtitleTrack?) -> Unit,
    currentVideoStream: VideoStream, onVideoStream: (VideoStream) -> Unit, showChapters: Boolean,
    onShowChapters: (Boolean) -> Unit, showQuality: Boolean, onShowQuality: (Boolean) -> Unit,
    showLanguage: Boolean, onShowLanguage: (Boolean) -> Unit, showCaptions: Boolean,
    onShowCaptions: (Boolean) -> Unit, showSpeed: Boolean, onShowSpeed: (Boolean) -> Unit,
    chrome: TvPlayerChrome,
) {
    TvPlayerCueOverlay(cues)
    TvPlayerBufferingSpinner(transport.buffering)
    val sheetState = TvPlayerOverlaySheetState(
        showChapters, onShowChapters, showQuality, onShowQuality, showLanguage, onShowLanguage,
        showCaptions, onShowCaptions, showSpeed, onShowSpeed,
    )
    val transportArgs = TvPlayerTransportArgs(
        videoInfo, chrome, transport, overlayVisible, subtitles.isNotEmpty(), selectedSubtitle != null,
        currentVideoStream.quality, segments.isNotEmpty(), languageEntries.size > 1, sheetState, ypvm,
    )
    TvPlayerTransportLayer(transportArgs)
    val sheetArgs = TvPlayerSheetArgs(
        segments, controller, videoStreams, currentVideoStream, onVideoStream, languageEntries,
        language, onLanguage, subtitles, selectedSubtitle, onSubtitle, transport.speed, sheetState,
    )
    TvPlayerSheets(sheetArgs)
}

@Composable
private fun BoxScope.TvPlayerCueOverlay(cues: List<Cue>) {
    if (cues.isNotEmpty()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp, start = 48.dp, end = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            cues.forEach { cue ->
                cue.text?.let { text ->
                    Text(
                        text = text.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.TvPlayerBufferingSpinner(buffering: Boolean) {
    if (buffering) {
        // TV media-surface buffering affordance: progress text, no spinner (TvLoading is screen-level only).
        Text("Loading\u2026", color = Color.White, modifier = Modifier.align(Alignment.Center))
    }
}

private data class TvPlayerOverlaySheetState(
    val showChapters: Boolean,
    val onShowChapters: (Boolean) -> Unit,
    val showQuality: Boolean,
    val onShowQuality: (Boolean) -> Unit,
    val showLanguage: Boolean,
    val onShowLanguage: (Boolean) -> Unit,
    val showCaptions: Boolean,
    val onShowCaptions: (Boolean) -> Unit,
    val showSpeed: Boolean,
    val onShowSpeed: (Boolean) -> Unit,
)

private data class TvPlayerTransportArgs(
    val videoInfo: VideoInfo,
    val chrome: TvPlayerChrome,
    val transport: CastPlayback.Transport,
    val overlayVisible: Boolean,
    val hasCaptions: Boolean,
    val captionsOn: Boolean,
    val qualityLabel: String,
    val hasChapters: Boolean,
    val hasLanguage: Boolean,
    val sheets: TvPlayerOverlaySheetState,
    val ypvm: YouPipeViewModel,
)

@Composable
private fun TvPlayerTransportLayer(args: TvPlayerTransportArgs) {
    FadeVisibility(visible = args.overlayVisible) {
        TvTransportOverlay(
            title = args.videoInfo.name,
            chrome = args.chrome,
            hasNext = args.transport.hasNext,
            hasPrevious = args.transport.hasPrevious,
            hasCaptions = args.hasCaptions,
            captionsOn = args.captionsOn,
            onCaptions = { args.sheets.onShowCaptions(true) },
            settings = TvPlayerSettings(
                qualityLabel = args.qualityLabel,
                speed = args.transport.speed,
                hasChapters = args.hasChapters,
                hasLanguage = args.hasLanguage,
                onQuality = { args.sheets.onShowQuality(true) },
                onSpeed = { args.sheets.onShowSpeed(true) },
                onLanguage = { args.sheets.onShowLanguage(true) },
                onChapters = { args.sheets.onShowChapters(true) },
            ),
            ypvm = args.ypvm,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private data class TvPlayerSheetArgs(
    val segments: List<VideoChapter>,
    val controller: MediaController?,
    val videoStreams: List<VideoStream>,
    val currentVideoStream: VideoStream,
    val onVideoStream: (VideoStream) -> Unit,
    val languageEntries: List<Pair<String, String>>,
    val language: String,
    val onLanguage: (String) -> Unit,
    val subtitles: List<SubtitleTrack>,
    val selectedSubtitle: SubtitleTrack?,
    val onSubtitle: (SubtitleTrack?) -> Unit,
    val speed: Float,
    val sheets: TvPlayerOverlaySheetState,
)

@Composable
private fun TvPlayerSheets(args: TvPlayerSheetArgs) {
    if (args.sheets.showChapters) {
        TvChapterSheet(
            segments = args.segments,
            onSeekTo = { args.controller?.seekTo(it) },
            onDismiss = { args.sheets.onShowChapters(false) },
        )
    }
    if (args.sheets.showQuality) {
        TvQualitySheet(
            streams = args.videoStreams, current = args.currentVideoStream,
            onSelect = { args.onVideoStream(it); args.sheets.onShowQuality(false) },
            onDismiss = { args.sheets.onShowQuality(false) },
        )
    }
    if (args.sheets.showLanguage) {
        TvLanguageSheet(
            entries = args.languageEntries, selected = args.language,
            onSelect = { args.onLanguage(it); args.sheets.onShowLanguage(false) },
            onDismiss = { args.sheets.onShowLanguage(false) },
        )
    }
    if (args.sheets.showCaptions) {
        TvCaptionSheet(
            tracks = args.subtitles, selected = args.selectedSubtitle,
            onSelect = { args.onSubtitle(it); args.sheets.onShowCaptions(false) },
            onDismiss = { args.sheets.onShowCaptions(false) },
        )
    }
    if (args.sheets.showSpeed) {
        TvSpeedSheet(
            current = args.speed,
            onSelect = { speed ->
                CastPlayback.update { it.copy(speed = speed) }
                args.sheets.onShowSpeed(false)
            },
            onDismiss = { args.sheets.onShowSpeed(false) },
        )
    }
}

/**
 * The 300ms poll loop: transport position, sponsor-block skips, periodic history writes.
 *
 * Mirrors the phone `VideoPlayerPositionLoop` exactly (same interval, same skip-while-sponsor
 * rule, same 5s history cadence) — the only reader difference is that the transport here is
 * also what the TV overlay draws, so the loop is the single writer of `positionMs`.
 */
@Composable
internal fun TvPlayerPositionLoop(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    controller: MediaController?,
) {
    val sponsorSegments by ypvm.videoState.collectAsState()
    val sponsorBlockEnabled by ypvm.sponsorBlockEnabled.collectAsState()
    val categories by ypvm.sponsorBlockCategories.collectAsState()
    val segments = remember(sponsorSegments.sponsorSegments, categories) {
        sponsorSegments.sponsorSegments.filter { it.category in categories }
    }
    // Persist the final position on dispose — the phone's HistoryPersist equivalent.
    val lastPositionState = remember(videoInfo.videoID) { mutableLongStateOf(0L) }
    val lastDurationState = remember(videoInfo.videoID) { mutableLongStateOf(0L) }
    TvPlayerHistoryPersistEffect(ypvm, videoInfo, lastPositionState, lastDurationState)
    val pollArgs = TvPlayerPollArgs(
        ypvm, videoInfo, segments, sponsorBlockEnabled, lastPositionState, lastDurationState,
    )
    TvPlayerPollEffect(controller, pollArgs)
}

@Composable
private fun TvPlayerHistoryPersistEffect(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    lastPositionState: MutableLongState,
    lastDurationState: MutableLongState,
) {
    DisposableEffect(videoInfo.videoID) {
        onDispose {
            ypvm.upsertHistoryVideo(
                com.vayunmathur.youpipe.data.HistoryVideo.fromVideoData(
                    videoInfo.copy(duration = lastDurationState.longValue),
                    lastPositionState.longValue,
                ),
            )
        }
    }
}

private data class TvPlayerPollArgs(
    val ypvm: YouPipeViewModel,
    val videoInfo: VideoInfo,
    val segments: List<com.vayunmathur.youpipe.network.SponsorSegment>,
    val sponsorBlockEnabled: Boolean,
    val lastPositionState: MutableLongState,
    val lastDurationState: MutableLongState,
)

@Composable
private fun TvPlayerPollEffect(controller: MediaController?, args: TvPlayerPollArgs) {
    androidx.compose.runtime.LaunchedEffect(controller) {
        val player = controller ?: return@LaunchedEffect
        var lastHistoryUpsert = 0L
        while (true) {
            var position = player.currentPosition.coerceAtLeast(0L)
            if (args.sponsorBlockEnabled) {
                val current = args.segments.find { position in it.start until it.end }
                if (current != null) {
                    player.seekTo(current.end)
                    position = current.end
                }
            }
            // A TV scrub preview freezes the loop's write so the bar does not fight the D-pad,
            // the same guard the phone's `dragging` provides against its own seek bar.
            if (TvDpadInput.scrubPreviewMs == null) {
                CastPlayback.update {
                    it.copy(
                        positionMs = position,
                        bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                    )
                }
            }
            args.lastPositionState.longValue = position
            args.lastDurationState.longValue = player.duration.coerceAtLeast(0L)
            if (player.isPlaying) {
                val now = System.currentTimeMillis()
                if (now - lastHistoryUpsert >= TV_HISTORY_UPSERT_INTERVAL_MS) {
                    args.ypvm.upsertHistoryVideo(
                        com.vayunmathur.youpipe.data.HistoryVideo.fromVideoData(
                            args.videoInfo.copy(duration = args.lastDurationState.longValue),
                            position,
                        ),
                    )
                    lastHistoryUpsert = now
                }
            }
            kotlinx.coroutines.delay(HISTORY_POLL_MS)
        }
    }
}

private const val TV_HISTORY_UPSERT_INTERVAL_MS = 5_000L

private const val HISTORY_POLL_MS = 300L
