package com.vayunmathur.youpipe.ui

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.content.ComponentName
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.ui.findActivity
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.platform.PlaybackService
import com.vayunmathur.youpipe.network.SponsorSegment
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlinx.coroutines.delay

/**
 * Controller, transport, history, media-item, subtitle and tempo effects for
 * [rememberVideoPlayerPlaybackState].
 *
 * Moved from VideoPlayerPlaybackState.kt (FileLength split); behavior identical, effect keys
 * unchanged. State objects (not snapshots) are passed in so dispose blocks still read the
 * latest values.
 */
@Composable
internal fun VideoPlayerControllerLifecycle(
    controllerState: MutableState<MediaController?>,
    wasInPipModeState: MutableState<Boolean>,
) {
    val context = LocalContext.current
    var controller by controllerState
    val wasInPipMode by wasInPipModeState
    DisposableEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            // The future may already be cancelled by onDispose's releaseFuture (controller
            // churn during navigation); a bare get() then throws and surfaces as an
            // AbstractFuture RuntimeException. Swallow cancellation, keep real failures loud.
            controller = try {
                controllerFuture.get()
            } catch (_: java.util.concurrent.CancellationException) {
                null
            } catch (e: java.util.concurrent.ExecutionException) {
                Log.error("YouPipePlayer", "MediaController connect failed", e)
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.error("YouPipePlayer", "MediaController connect interrupted", e)
                null
            }
        }, MoreExecutors.directExecutor())

        onDispose {
            // If we were ever in PiP (including PiP closed -> background audio), don't stop the
            // ExoPlayer inside the service. Just release the controller connection. The service
            // will itself switch to audio-only when PiP is dismissed to save bandwidth.
            val activity = try { context.findActivity() } catch (_: Exception) { null }
            val keepPlaying = wasInPipMode || activity?.isInPictureInPictureMode == true
            if (keepPlaying) {
                controller?.let {
                    // Disable video track to stop streaming video (audio-only background)
                    try {
                        it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                            .build()
                    } catch (_: Exception) {}
                    it.release()
                }
            } else {
                controller?.let {
                    it.stop()
                    it.release()
                }
            }
            MediaController.releaseFuture(controllerFuture)
        }
    }
}

/** Observes the controller into the shared transport, aspect ratio and cues. */
@Composable
internal fun VideoPlayerTransportObserver(
    controller: MediaController?,
    aspectRatioState: MutableFloatState,
    cuesState: MutableState<List<Cue>>,
) {
    val context = LocalContext.current
    var aspectRatio by aspectRatioState
    var cues by cuesState
    // #565: a mid-playback SABR failure used to surface as a silent stall (the spinner spins,
    // nothing says why). Surface ExoPlayer errors as a message so the user knows playback died.
    val playbackErrorMessage = stringResource(R.string.playback_error)
    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}
        // Sync initial playing state once controller is available
        CastPlayback.update { it.copy(playing = player.isPlaying) }
        // What a remote command is applied to, so the TV drives the same transport these buttons do.
        CastPlayback.attachPlayer(player)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    CastPlayback.update {
                        it.copy(
                            durationMs = player.duration.coerceAtLeast(0L),
                            buffering = player.playbackState == Player.STATE_BUFFERING,
                            playing = player.isPlaying,
                        )
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.error("YouPipePlayer", "Playback error", error)
                AppMessages.show(playbackErrorMessage)
            }
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                CastPlayback.update { it.copy(playing = isPlayingNow) }
                val activity = context.findActivity()
                val sourceRect = Rect()
                activity.window.decorView.getGlobalVisibleRect(sourceRect)
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().apply {
                    setAutoEnterEnabled(isPlayingNow)
                    if (!sourceRect.isEmpty) setSourceRectHint(sourceRect)
                }.build())
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat() * videoSize.pixelWidthHeightRatio
            }
            override fun onCues(cueGroup: CueGroup) {
                Log.dev("YouPipeSubs", "onCues called with ${cueGroup.cues.size} cues")
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            CastPlayback.detachPlayer(player)
            val activity = context.findActivity()
            val sourceRect = Rect()
            activity.window.decorView.getGlobalVisibleRect(sourceRect)
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().apply {
                setAutoEnterEnabled(false)
                if (!sourceRect.isEmpty) setSourceRectHint(sourceRect)
            }.build())
        }
    }
}

/** The 300 ms poll loop: transport position, sponsor-block skips and periodic history writes. */
@Composable
internal fun VideoPlayerPositionLoop(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    controller: MediaController?,
    isDragging: Boolean,
    sponsorBlockEnabled: Boolean,
    sponsorSegments: List<SponsorSegment>,
    historyPositionState: MutableLongState,
    historyDurationState: MutableLongState,
) {
    var historyPosition by historyPositionState
    var historyDuration by historyDurationState
    LaunchedEffect(controller, isDragging) {
        val player = controller ?: return@LaunchedEffect
        var lastHistoryUpsert = 0L
        while (true) {
            if (!isDragging) {
                var position = player.currentPosition.coerceAtLeast(0L)

                if (sponsorBlockEnabled) {
                    val currentSegment = sponsorSegments.find { position in it.start until it.end }
                    if (currentSegment != null) {
                        player.seekTo(currentSegment.end)
                        position = currentSegment.end
                    }
                }
                CastPlayback.update {
                    it.copy(
                        positionMs = position,
                        bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                    )
                }
                historyPosition = position
                historyDuration = player.duration.coerceAtLeast(0L)

                if (player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastHistoryUpsert >= HISTORY_UPSERT_INTERVAL_MS) {
                        ypvm.upsertHistoryVideo(HistoryVideo.fromVideoData(videoInfo.copy(duration = historyDuration), position))
                        lastHistoryUpsert = now
                    }
                }
            }
            delay(300)
        }
    }
}

/** Persists the final watch position once when leaving the player. */
@Composable
internal fun VideoPlayerHistoryPersist(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    historyPositionState: MutableLongState,
    historyDurationState: MutableLongState,
) {
    DisposableEffect(videoInfo.videoID) {
        onDispose {
            ypvm.upsertHistoryVideo(
                HistoryVideo.fromVideoData(
                    videoInfo.copy(duration = historyDurationState.longValue),
                    historyPositionState.longValue,
                ),
            )
        }
    }
}

/** Builds the media item (audio URI in metadata extras, subtitle configs) and prepares it. */
@Composable
internal fun VideoPlayerMediaItemEffect(
    controller: MediaController?,
    currentVideoStream: VideoStream,
    currentAudioStream: AudioStream?,
    videoInfo: VideoInfo,
    subtitles: List<SubtitleTrack>,
    timeWatched: Long,
) {
    // 3. Updated LaunchedEffect to pass audio URI through Metadata Extras
    LaunchedEffect(controller, currentVideoStream, currentAudioStream, videoInfo.name, videoInfo.author, subtitles) {
        val player = controller ?: return@LaunchedEffect

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(videoInfo.name)
            .setArtist(videoInfo.author)

        currentAudioStream?.let { audio ->
            val extras = Bundle().apply {
                putString("extra_audio_uri", audio.url)
                putString("extra_audio_track_id", audio.audioTrackId)
                // Parse itag from sabr:// URL for direct selection
                try {
                    val aUri = audio.url.toUri()
                    aUri.getQueryParameter("a")?.toIntOrNull()?.let { putInt("extra_audio_itag", it) }
                } catch (_: Exception) {}
            }
            metadataBuilder.setExtras(extras)
        }

        Log.dev("YouPipeSubs", "Building ${subtitles.size} subtitle configs")
        val subtitleConfigs = subtitles.map { sub ->
            Log.dev("YouPipeSubs", "Sub: lang=${sub.languageTag} mime=${sub.mimeType} url=${sub.url} auto=${sub.autoGenerated}")
            MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
                .setMimeType(sub.mimeType)
                .setLanguage(sub.languageTag)
                .setRoleFlags(if (sub.autoGenerated) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else C.ROLE_FLAG_CAPTION)
                .build()
        }

        val mediaItem = MediaItem.Builder()
            .setUri(currentVideoStream.url)
            .setSubtitleConfigurations(subtitleConfigs)
            .setMediaMetadata(metadataBuilder.build())
            .build()

        player.setMediaItem(mediaItem, timeWatched)
        player.prepare()
        CastPlayback.update { it.copy(positionMs = timeWatched) }
    }
}

/** Applies the selected subtitle track to the controller. */
@Composable
internal fun VideoPlayerSubtitleEffect(
    controller: MediaController?,
    selectedSubtitle: SubtitleTrack?,
) {
    LaunchedEffect(controller, selectedSubtitle) {
        val player = controller ?: return@LaunchedEffect
        Log.dev("YouPipeSubs", "Selected subtitle changed: ${selectedSubtitle?.languageTag} url=${selectedSubtitle?.url}")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            val sub = selectedSubtitle
            if (sub == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setPreferredTextLanguage(sub.languageTag)
            }
        }.build()
    }
}

/** Applies tempo/pitch whenever they change (or the controller connects). */
@Composable
internal fun VideoPlayerTempoEffect(
    controller: MediaController?,
    playbackSpeed: Float,
    playbackPitch: Float,
) {
    LaunchedEffect(controller, playbackSpeed, playbackPitch) {
        controller?.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)
    }
}
