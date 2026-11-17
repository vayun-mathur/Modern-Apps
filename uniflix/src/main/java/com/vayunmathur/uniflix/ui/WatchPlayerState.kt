package com.vayunmathur.uniflix.ui

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.uniflix.domain.SubtitleTrack
import com.vayunmathur.uniflix.domain.VideoSource
import kotlinx.coroutines.delay

private const val TAG = "WatchPlayer"
private const val POSITION_POLL_MS = 300L
private const val RESOLUTION_MAX_WIDTH = 4096

/** Best-effort MIME for a sidecar track from its URL; ExoPlayer sniffs the rest. */
internal fun subtitleMime(url: String): String {
    val lower = url.substringBefore('?').lowercase()
    return when {
        lower.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        lower.endsWith(".ttml") || lower.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
}

/**
 * Playback-owned state for [WatchScreen]: the ExoPlayer lifecycle, transport
 * snapshots, cues, available rendition heights and the last player error.
 *
 * The player is rebuilt only when the stream URL changes (or [ticket] bumps
 * for retry); every per-player snapshot is keyed the same way so a rebuild
 * never shows the previous stream's position, cues or error.
 */
internal class WatchPlayerState(
    val player: ExoPlayer?,
    positionState: MutableLongState,
    bufferedState: MutableLongState,
    durationState: MutableLongState,
    playingState: MutableState<Boolean>,
    bufferingState: MutableState<Boolean>,
    draggingState: MutableState<Boolean>,
    cuesState: MutableState<List<Cue>>,
    heightsState: MutableState<List<Int>>,
    errorState: MutableState<String?>,
) {
    var positionMs: Long by positionState
    var bufferedMs: Long by bufferedState
    var durationMs: Long by durationState
    var isPlaying: Boolean by playingState
    var isBuffering: Boolean by bufferingState
    var isDragging: Boolean by draggingState
    var cues: List<Cue> by cuesState
    val videoHeights: List<Int> by heightsState
    var playerError: String? by errorState

    fun togglePlay() {
        val player = player ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(position: Long) {
        player?.seekTo(position.coerceAtLeast(0L))
    }

    fun skipBy(deltaMs: Long) {
        val player = player ?: return
        val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, end))
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun rememberWatchPlayerState(
    source: VideoSource?,
    startPositionMs: Long,
    selectedSubtitle: SubtitleTrack?,
    maxHeight: Int?,
    ticket: Int,
    onRelease: (positionMs: Long, durationMs: Long) -> Unit,
): WatchPlayerState {
    val context = LocalContext.current
    val url = source?.streamUrl

    val player = remember(url, ticket) {
        if (source == null) {
            null
        } else {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(DefaultHttpDataSource.Factory()),
                )
                .build()
                .apply {
                    val subtitleConfigs = source.subtitles.map { sub ->
                        MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
                            .setMimeType(subtitleMime(sub.url))
                            .setLanguage(sub.language.ifBlank { "en" })
                            .build()
                    }
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(source.streamUrl)
                            .setSubtitleConfigurations(subtitleConfigs)
                            .build(),
                        startPositionMs.coerceAtLeast(0L),
                    )
                    trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                        val sub = selectedSubtitle
                        if (sub == null) {
                            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        } else {
                            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            setPreferredTextLanguage(sub.language)
                        }
                        if (maxHeight != null) setMaxVideoSize(RESOLUTION_MAX_WIDTH, maxHeight)
                    }.build()
                    prepare()
                    play()
                }
        }
    }

    val positionState = remember(url, ticket) { mutableLongStateOf(0L) }
    val bufferedState = remember(url, ticket) { mutableLongStateOf(0L) }
    val durationState = remember(url, ticket) { mutableLongStateOf(0L) }
    val playingState = remember(url, ticket) { mutableStateOf(true) }
    val bufferingState = remember(url, ticket) { mutableStateOf(true) }
    val draggingState = remember { mutableStateOf(false) }
    val cuesState = remember(url, ticket) { mutableStateOf(emptyList<Cue>()) }
    val heightsState = remember(url, ticket) { mutableStateOf(emptyList<Int>()) }
    val errorState = remember(url, ticket) { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}
        playingState.value = player.isPlaying
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playingState.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                bufferingState.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationState.longValue = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.error(TAG, "Playback error: ${error.message}", error)
                AppMessages.show("Playback failed: ${error.message}")
                errorState.value = error.message ?: "Playback failed"
            }

            override fun onTracksChanged(tracks: Tracks) {
                heightsState.value = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()
            }

            override fun onCues(cueGroup: CueGroup) {
                cuesState.value = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            onRelease(player.currentPosition.coerceAtLeast(0L), player.duration.coerceAtLeast(0L))
            player.release()
        }
    }

    LaunchedEffect(player, selectedSubtitle) {
        if (player == null) return@LaunchedEffect
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            val sub = selectedSubtitle
            if (sub == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setPreferredTextLanguage(sub.language)
            }
        }.build()
    }

    LaunchedEffect(player, maxHeight) {
        if (player == null) return@LaunchedEffect
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (maxHeight == null) {
                clearVideoSizeConstraints()
            } else {
                setMaxVideoSize(RESOLUTION_MAX_WIDTH, maxHeight)
            }
        }.build()
    }

    LaunchedEffect(player) {
        val active = player ?: return@LaunchedEffect
        while (true) {
            if (!draggingState.value) {
                positionState.longValue = active.currentPosition.coerceAtLeast(0L)
                bufferedState.longValue = active.bufferedPosition.coerceAtLeast(0L)
                durationState.longValue = active.duration.coerceAtLeast(0L)
            }
            delay(POSITION_POLL_MS)
        }
    }

    return WatchPlayerState(
        player = player,
        positionState = positionState,
        bufferedState = bufferedState,
        durationState = durationState,
        playingState = playingState,
        bufferingState = bufferingState,
        draggingState = draggingState,
        cuesState = cuesState,
        heightsState = heightsState,
        errorState = errorState,
    )
}
