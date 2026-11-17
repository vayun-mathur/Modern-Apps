package com.vayunmathur.youpipe.ui

import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastContract
import com.vayunmathur.sdk.cast.CastPickerContract
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.findActivity
import com.vayunmathur.youpipe.ui.rememberIsInPipMode
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * View-owned state for [VideoPlayer]: menus, lock, hover, the keep-screen-on flag, the cast
 * session and the controls auto-hide timer.
 *
 * Moved from VideoPlayer.kt (FileLength split); behavior identical. See
 * [rememberVideoPlayerPlaybackState] for the playback-owned half.
 */
internal class VideoPlayerViewState(
    isVideoMenuExpandedState: androidx.compose.runtime.MutableState<Boolean>,
    isLanguageMenuExpandedState: androidx.compose.runtime.MutableState<Boolean>,
    isChapterMenuVisibleState: androidx.compose.runtime.MutableState<Boolean>,
    isCaptionMenuExpandedState: androidx.compose.runtime.MutableState<Boolean>,
    isSpeedMenuExpandedState: androidx.compose.runtime.MutableState<Boolean>,
    isLockedState: androidx.compose.runtime.MutableState<Boolean>,
    isControlsVisibleState: androidx.compose.runtime.MutableState<Boolean>,
    controlsInteractionTickState: androidx.compose.runtime.MutableIntState,
    hoverSource: MutableInteractionSource,
    val isHovered: Boolean,
    val isPipMode: Boolean,
    val scope: CoroutineScope,
    val castState: CastPlayback.State,
    val isCasting: Boolean,
    val castFailedMessage: String,
    val castInstallMessage: String,
    val castUpdateMessage: String,
    val castPicker: androidx.activity.result.ActivityResultLauncher<Unit>,
    val keepControlsVisible: Boolean,
) {
    var isVideoMenuExpanded: Boolean by isVideoMenuExpandedState
    var isLanguageMenuExpanded: Boolean by isLanguageMenuExpandedState
    var isChapterMenuVisible: Boolean by isChapterMenuVisibleState
    var isCaptionMenuExpanded: Boolean by isCaptionMenuExpandedState
    var isSpeedMenuExpanded: Boolean by isSpeedMenuExpandedState
    var isLocked: Boolean by isLockedState
    var isControlsVisible: Boolean by isControlsVisibleState
    var controlsInteractionTick: Int by controlsInteractionTickState
    val hover: MutableInteractionSource = hoverSource
}

@Composable
internal fun rememberVideoPlayerViewState(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    currentVideoStream: VideoStream,
    aspectRatio: Float,
    controller: MediaController?,
    isPlaying: Boolean,
    isDragging: Boolean,
): VideoPlayerViewState {
    val context = LocalContext.current

    val isVideoMenuExpandedState = remember { mutableStateOf(false) }
    var isVideoMenuExpanded by isVideoMenuExpandedState
    val isLanguageMenuExpandedState = remember { mutableStateOf(false) }
    var isLanguageMenuExpanded by isLanguageMenuExpandedState
    val isChapterMenuVisibleState = remember { mutableStateOf(false) }
    var isChapterMenuVisible by isChapterMenuVisibleState
    val isCaptionMenuExpandedState = remember { mutableStateOf(false) }
    var isCaptionMenuExpanded by isCaptionMenuExpandedState
    val isSpeedMenuExpandedState = remember { mutableStateOf(false) }
    var isSpeedMenuExpanded by isSpeedMenuExpandedState
    val isControlsVisibleState = remember { mutableStateOf(true) }
    var isControlsVisible by isControlsVisibleState
    val controlsInteractionTickState = remember { mutableIntStateOf(0) }
    var controlsInteractionTick by controlsInteractionTickState
    val keepControlsVisible by ypvm.keepPlayerControlsVisible.collectAsState()

    // Lock hides all controls (and disables seek/speed gestures) so a fullscreen video can't be
    // disturbed by accidental touches, until the user taps the on-screen unlock button.
    val isLockedState = remember { mutableStateOf(false) }
    var isLocked by isLockedState

    // Desktop: hovering the player with a mouse keeps the controls up the way a finger
    // tap does on touch — the auto-hide timer below stands down while hovered.
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()

    val activity = context.findActivity()
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val isPipMode = rememberIsInPipMode()
    val scope = rememberCoroutineScope()

    // ---- casting ----
    // Cast state lives beside the transport state because that is where VideoPlayer keeps everything
    // else about this one player - but in a singleton rather than a `remember`, since the PCM half of
    // the session is produced inside PlaybackService's audio sink, which has no reference to any of
    // this.
    val castState by CastPlayback.state.collectAsState()
    val isCasting = castState is CastPlayback.State.Casting
    // Resolved here rather than inside the callbacks: reading resources off a Context in a composable
    // misses configuration changes, and lint says so.
    val castFailedMessage = stringResource(R.string.cast_failed)
    val castInstallMessage = stringResource(R.string.cast_install_prompt)
    val castUpdateMessage = stringResource(R.string.cast_update_prompt)
    val castPicker = rememberLauncherForActivityResult(CastPickerContract()) { picked ->
        if (!picked) {
            // Backed out, or pairing failed. Nothing should change in the player.
            CastPlayback.close()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val (requestedWidth, requestedHeight) = castRequestSize(currentVideoStream, aspectRatio)
            if (CastPlayback.open(context, requestedWidth, requestedHeight) != null) {
                AppMessages.show(castFailedMessage)
            }
        }
    }
    // Moving playback is one call. ExoPlayer keeps decoding and keeps its transport controls, so play,
    // pause and seek still work and still control the remote picture; only the render target changes.
    // Local audio is muted with volume rather than by stopping the renderer, because volume is applied
    // in the sink *after* the processor chain - so CastAudioTap still sees full-scale PCM.
    LaunchedEffect(controller, castState) {
        val player = controller ?: return@LaunchedEffect
        val casting = castState as? CastPlayback.State.Casting
        if (casting != null) {
            Log.status(
                "YpCastDiag",
                "handing the cast surface to the player: valid=${casting.surface.isValid} " +
                    "${casting.width}x${casting.height}",
            )
            player.setVideoSurface(casting.surface)
            player.volume = 0f
        } else {
            player.volume = 1f
        }
    }
    // TEMPORARY DIAGNOSTICS. Answers the one thing the logs cannot: whether the player ever draws a
    // frame into the encoder's input surface. Remove once the no-video-on-cast fault is understood.
    DisposableEffect(controller, isCasting) {
        val player = controller
        if (player == null || !isCasting) return@DisposableEffect onDispose { }
        Log.status(
            "YpCastDiag",
            "attached; videoSize=${player.videoSize.width}x${player.videoSize.height} " +
                "playing=${player.isPlaying} state=${player.playbackState}",
        )
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                Log.status("YpCastDiag", "onRenderedFirstFrame")
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                Log.status(
                    "YpCastDiag",
                    "onVideoSizeChanged ${videoSize.width}x${videoSize.height}",
                )
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                Log.status("YpCastDiag", "onSurfaceSizeChanged ${width}x$height")
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val video = tracks.groups.count {
                    it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO
                }
                val selected = tracks.groups.count {
                    it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected
                }
                Log.status(
                    "YpCastDiag",
                    "onTracksChanged videoGroups=$video selectedVideoGroups=$selected",
                )
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // What the television puts where "Receiving from YouPipe" used to be. Keyed on the video as well
    // as the session, because a `Next` from the remote reloads this player in place rather than
    // advancing a queue - so the title would otherwise describe the previous video for the rest of the
    // cast. Sending it again is free: it is an absolute snapshot that replaces wholesale.
    LaunchedEffect(castState, videoInfo.name, videoInfo.author) {
        if (castState is CastPlayback.State.Casting) {
            CastPlayback.setNowPlaying(videoInfo.name, videoInfo.author)
        }
    }
    // No DisposableEffect closing the cast here any more. Leaving a video used to end the session, on
    // the reasoning that there is one video output and it cannot follow the user to another screen -
    // which also meant a "next" from the television dropped the cast. CastPlayback now holds the
    // session briefly when nothing is drawing into it, so a replacement player takes it over and only
    // genuinely walking away ends it.

    // When paused, keep controls visible (no auto-hide). When playing resumes, re-show and start timer.
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            isControlsVisible = true
            controlsInteractionTick++
        } else {
            isControlsVisible = true
        }
    }

    // Auto-hide controls after 2 seconds of inactivity while playing. Resets on any interaction.
    LaunchedEffect(
        isControlsVisible, isPlaying, keepControlsVisible, isLocked, isDragging,
        isVideoMenuExpanded, isLanguageMenuExpanded, isCaptionMenuExpanded,
        isSpeedMenuExpanded, isChapterMenuVisible, isPipMode, controlsInteractionTick,
        isCasting, isHovered
    ) {
        if (!isControlsVisible) return@LaunchedEffect
        if (keepControlsVisible) return@LaunchedEffect
        if (isLocked) return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        if (isDragging) return@LaunchedEffect
        if (isVideoMenuExpanded || isLanguageMenuExpanded || isCaptionMenuExpanded || isSpeedMenuExpanded || isChapterMenuVisible) return@LaunchedEffect
        if (isPipMode) return@LaunchedEffect
        // While casting there is no picture here to get out of the way of, and the only way to stop is
        // a button in these controls.
        if (isCasting) return@LaunchedEffect
        // A hovering mouse is an engaged viewer: keep the controls up while it is over the player.
        if (isHovered) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_DELAY_MS)
        isControlsVisible = false
    }

    // Accessibility toggle: when "keep controls visible" is enabled, force controls on.
    LaunchedEffect(keepControlsVisible) {
        if (keepControlsVisible) {
            isControlsVisible = true
        } else {
            // Re-arm timer when re-enabling auto-hide while playing.
            if (isPlaying && isControlsVisible) controlsInteractionTick++
        }
    }

    return VideoPlayerViewState(
        isVideoMenuExpandedState = isVideoMenuExpandedState,
        isLanguageMenuExpandedState = isLanguageMenuExpandedState,
        isChapterMenuVisibleState = isChapterMenuVisibleState,
        isCaptionMenuExpandedState = isCaptionMenuExpandedState,
        isSpeedMenuExpandedState = isSpeedMenuExpandedState,
        isLockedState = isLockedState,
        isControlsVisibleState = isControlsVisibleState,
        controlsInteractionTickState = controlsInteractionTickState,
        hoverSource = hoverSource,
        isHovered = isHovered,
        isPipMode = isPipMode,
        scope = scope,
        castState = castState,
        isCasting = isCasting,
        castFailedMessage = castFailedMessage,
        castInstallMessage = castInstallMessage,
        castUpdateMessage = castUpdateMessage,
        castPicker = castPicker,
        keepControlsVisible = keepControlsVisible,
    )
}
