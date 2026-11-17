package com.vayunmathur.youpipe.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastContract
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player shell: transport/playback state comes from [VideoPlayerPlaybackState], view state
 * (menus, lock, hover, cast session, controls timer) from [VideoPlayerViewState].
 *
 * Split from the former 779-line file (FileLength); behavior identical.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    subtitles: List<SubtitleTrack>,
    segments: List<VideoChapter>,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    if (videoStreams.isEmpty()) return

    val pb = rememberVideoPlayerPlaybackState(ypvm, videoInfo, videoStreams, audioStreams, subtitles)
    val vs = rememberVideoPlayerViewState(
        ypvm, videoInfo, pb.currentVideoStream, pb.aspectRatio, pb.controller, pb.isPlaying,
        pb.isDragging,
    )
    with(pb) {
    with(vs) {
    val context = LocalContext.current
    val viewConfiguration = LocalViewConfiguration.current
    val hasAudio = audioStreams.isNotEmpty()
    val useNotificationStyleBar by ypvm.notificationStyleProgressBar.collectAsState()

    val modifier = if (isFullscreen) Modifier.fillMaxHeight() else Modifier.aspectRatio(aspectRatio)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(hover)
            .pointerInput(isLocked, keepControlsVisible) {
                detectTapGestures(
                    onTap = {
                        if (isLocked) {
                            isControlsVisible = true
                            controlsInteractionTick++
                            return@detectTapGestures
                        }
                        if (isControlsVisible) {
                            // When auto-hide is disabled for accessibility, tapping keeps controls visible and resets timer.
                            if (keepControlsVisible) {
                                controlsInteractionTick++
                            } else {
                                isControlsVisible = false
                            }
                        } else {
                            isControlsVisible = true
                            controlsInteractionTick++
                        }
                    },
                    onDoubleTap = { offset ->
                        if (isLocked) return@detectTapGestures
                        // Any gesture re-shows controls and resets auto-hide timer.
                        isControlsVisible = true
                        controlsInteractionTick++
                        // Read position and duration from the player at tap time, not from the
                        // snapshots captured when this gesture block was keyed: those go stale and
                        // a seek from ~0 makes the video appear to restart instead of skipping (#809).
                        // Keys stay stable on purpose: re-keying per position tick would restart
                        // gesture detection every 300 ms and break double-tap/long-press outright.
                        val player = controller ?: return@detectTapGestures
                        val target = doubleTapSeekTarget(
                            player.currentPosition,
                            player.duration,
                            offset.x > size.width / 2,
                        )
                        player.seekTo(target)
                        CastPlayback.update { it.copy(positionMs = target) }
                    },
                    onPress = {
                        if (isLocked) return@detectTapGestures
                        val job = scope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            controller?.setPlaybackSpeed(2f)
                        }
                        try {
                            awaitRelease()
                        } finally {
                            job.cancel()
                            // Restore the user's chosen tempo/pitch, not a hardcoded 1x.
                            controller?.playbackParameters = PlaybackParameters(playbackSpeed, playbackPitch)
                        }
                    }
                )
            }
    ) {
        val playerModifier = if (aspectRatio > 16f / 9f) {
            // Video is wider than container -> match width, height will follow ratio
            Modifier.fillMaxWidth().aspectRatio(aspectRatio)
        } else {
            // Video is taller than container -> match height, width will follow ratio
            Modifier.fillMaxHeight().aspectRatio(aspectRatio)
        }
        controller?.let { player ->
            // While casting there is no local picture: there is one video output and it is the TV, which
            // is also what the user expects to see when they have just cast something.
            val casting = castState as? CastPlayback.State.Casting
            if (casting != null) {
                CastingPanel(
                    receiverName = casting.receiverName,
                    onStop = { CastPlayback.close() },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlayerSurface(
                    player = player,
                    modifier = playerModifier.align(Alignment.Center),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW
                )
            }
        } ?: Box(modifier = Modifier.fillMaxSize().background(Color.Black))

        if (cues.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                cues.forEach { cue ->
                    cue.text?.let { text ->
                        Text(
                            text = text.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        FadeVisibility(visible = isControlsVisible && !isPipMode && !isLocked) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                VideoPlayerTopControls(
                    hasAudio = hasAudio,
                    languages = languages,
                    languageEntries = languageEntries,
                    language = language,
                    onLanguageChange = { language = it },
                    isVideoMenuExpanded = isVideoMenuExpanded,
                    onVideoMenuExpandedChange = { isVideoMenuExpanded = it },
                    isLanguageMenuExpanded = isLanguageMenuExpanded,
                    onLanguageMenuExpandedChange = { isLanguageMenuExpanded = it },
                    isCaptionMenuExpanded = isCaptionMenuExpanded,
                    onCaptionMenuExpandedChange = { isCaptionMenuExpanded = it },
                    isSpeedMenuExpanded = isSpeedMenuExpanded,
                    onSpeedMenuExpandedChange = { isSpeedMenuExpanded = it },
                    currentVideoStream = currentVideoStream,
                    onVideoStreamChange = { currentVideoStream = it },
                    videoStreams = videoStreams,
                    subtitles = subtitles,
                    selectedSubtitle = selectedSubtitle,
                    onSubtitleChange = { selectedSubtitle = it },
                    segments = segments,
                    onChapterMenuVisibleChange = { isChapterMenuVisible = it },
                    playbackSpeed = playbackSpeed,
                    onSpeedChange = { CastPlayback.update { state -> state.copy(speed = it) } },
                    playbackPitch = playbackPitch,
                    onPitchChange = { playbackPitch = it },
                    unhookPitch = unhookPitch,
                    onUnhookPitchChange = {
                        unhookPitch = it
                        if (!it) playbackPitch = playbackSpeed
                    },
                    isCasting = isCasting,
                    onCastClick = {
                        controlsInteractionTick++
                        when (CastPlayback.support(context)) {
                            CastClient.Support.NOT_INSTALLED -> {
                                AppMessages.show(castInstallMessage)
                                ExternalIntents.openAppListing(context, CastContract.CAST_PACKAGE)
                            }
                            CastClient.Support.NEEDS_UPDATE -> {
                                AppMessages.show(castUpdateMessage)
                                ExternalIntents.openAppListing(context, CastContract.CAST_PACKAGE)
                            }
                            CastClient.Support.READY ->
                                if (isCasting) {
                                    CastPlayback.close()
                                } else {
                                    CastPlayback.markConnecting()
                                    castPicker.launch(Unit)
                                }
                        }
                    },
                )

                controller?.let { player ->
                    PlayPauseButton(
                        player = player,
                        modifier = Modifier.size(64.dp).align(Alignment.Center),
                    )
                }

                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp).align(Alignment.Center),
                        color = Color.White
                    )
                }
                VideoPlayerSeekRow(
                    currentPosition = currentPosition,
                    bufferedPosition = bufferedPosition,
                    duration = duration,
                    isPlaying = isPlaying,
                    useNotificationStyle = useNotificationStyleBar,
                    sponsorSegments = sponsorSegments,
                    onSeekChange = { position ->
                        CastPlayback.update { state ->
                            state.copy(dragging = true, positionMs = position)
                        }
                    },
                    onSeekFinished = {
                        controller?.seekTo(currentPosition)
                        CastPlayback.update { it.copy(dragging = false) }
                    },
                    onSeekTo = { position ->
                        controller?.seekTo(position)
                        CastPlayback.update { it.copy(positionMs = position) }
                    },
                    isFullscreen = isFullscreen,
                    onLock = {
                        isLocked = true
                        isControlsVisible = false
                    },
                    onFullscreenChange = onFullscreenChange,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }

        FadeVisibility(visible = isLocked && isControlsVisible && !isPipMode) {
            Box(modifier = Modifier.fillMaxSize()) {
                IconButton(
                    onClick = {
                        isLocked = false
                        isControlsVisible = true
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape)
                        .size(56.dp)
                ) {
                    IconLock(tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }

        FadeVisibility(visible = isChapterMenuVisible && !isPipMode) {
            VideoPlayerChapterSheet(
                segments = segments,
                currentPosition = currentPosition,
                onSeekTo = { controller?.seekTo(it) },
                onDismiss = { isChapterMenuVisible = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    }
    }
}

/** Cast-request sizing, the casting panel and tempo formatting live in VideoPlayerCasting.kt. */

/** How far one double-tap on either side of the player moves playback. */
internal const val DOUBLE_TAP_SKIP_MS = 10_000L

/**
 * Where a double-tap skip lands, given the position at tap time.
 *
 * Pure so it is unit-testable; the gesture handler reads the live player position and clamps here.
 * An unknown duration ([durationMs] <= 0, e.g. `C.TIME_UNSET`) means forward seeks are unbounded
 * and backward seeks still floor at zero.
 */
internal fun doubleTapSeekTarget(currentPosition: Long, durationMs: Long, forward: Boolean): Long {
    val end = durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
    return if (forward) {
        (currentPosition + DOUBLE_TAP_SKIP_MS).coerceAtMost(end)
    } else {
        (currentPosition - DOUBLE_TAP_SKIP_MS).coerceAtLeast(0L)
    }
}
