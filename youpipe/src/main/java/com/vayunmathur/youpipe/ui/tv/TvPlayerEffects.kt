package com.vayunmathur.youpipe.ui.tv
import android.content.ComponentName
import android.content.Context
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.youpipe.ui.findActivity
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.selectDefaultVideoStream
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.platform.PlaybackService
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlin.math.min

@Composable
internal fun TvPlayerControllerEffect(
    controllerState: MutableState<MediaController?>,
    context: Context,
    playbackErrorMessage: String,
) {
    // Controller lifecycle: same SessionToken → PlaybackService connect as the phone.
    DisposableEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        var disposed = false
        future.addListener({
            val c = try {
                future.get()
            } catch (_: java.util.concurrent.CancellationException) {
                null
            } catch (_: Exception) {
                AppMessages.show(playbackErrorMessage)
                null
            }
            if (c == null) return@addListener
            if (disposed) {
                c.stop()
                c.release()
            } else {
                controllerState.value = c
            }
        }, MoreExecutors.directExecutor())
        onDispose {
            disposed = true
            controllerState.value?.let { it.stop(); it.release() }
            MediaController.releaseFuture(future)
        }
    }
}

@Composable
internal fun TvPlayerMediaItemEffect(args: TvPlayerMediaArgs, startedState: MutableState<Boolean>) {
    // Media item: same extras contract (audio URI + itag) the SABR source resolves in-process.
    // The first load starts at the resume point; a later reload (quality, language, captions)
    // keeps the current position instead of restarting the video.
    LaunchedEffect(args.controller, args.videoStream, args.audioStream, args.videoInfo.name, args.subtitles) {
        val player = args.controller ?: return@LaunchedEffect
        val metadata = MediaMetadata.Builder()
            .setTitle(args.videoInfo.name)
            .setArtist(args.videoInfo.author)
            .build()
        val item = MediaItem.Builder()
            .setUri(args.videoStream.url)
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(
                args.subtitles.map { sub ->
                    MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
                        .setMimeType(sub.mimeType)
                        .setLanguage(sub.languageTag)
                        .build()
                },
            )
            .build()
        val startAt = if (startedState.value) player.currentPosition.coerceAtLeast(0L) else args.startPositionMs
        startedState.value = true
        player.setMediaItem(item, startAt)
        player.prepare()
        player.play()
    }
}

@Composable
internal fun TvPlayerTransportObserverEffect(controller: MediaController?, onCues: (List<Cue>) -> Unit) {
    // Transport observer: aspect, cues, playing/buffering into the shared transport.
    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}
        CastPlayback.update { it.copy(playing = player.isPlaying) }
        CastPlayback.attachPlayer(player)
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                ) {
                    CastPlayback.update {
                        it.copy(
                            durationMs = player.duration.coerceAtLeast(0L),
                            buffering = player.playbackState == Player.STATE_BUFFERING,
                            playing = player.isPlaying,
                        )
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                CastPlayback.update { it.copy(playing = isPlaying) }
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                onCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            CastPlayback.detachPlayer(player)
        }
    }
}

@Composable
internal fun TvPlayerTrackAndSpeedEffects(
    controller: MediaController?,
    selectedSubtitle: SubtitleTrack?,
    speed: Float,
) {
    // Subtitle + tempo application.
    LaunchedEffect(controller, selectedSubtitle) {
        val player = controller ?: return@LaunchedEffect
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (selectedSubtitle == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setPreferredTextLanguage(selectedSubtitle!!.languageTag)
            }
        }.build()
    }
    LaunchedEffect(controller, speed) {
        val pitch = speed
        controller?.playbackParameters = PlaybackParameters(speed, pitch)
    }
}

@Composable
internal fun TvPlayerOverlayDismissEffects(sheets: TvPlayerSheetState, overlayVisible: Boolean) {
    // BACK dismisses the topmost overlay first: option sheets, then the controls.
    val anySheet = sheets.showChapters || sheets.showQuality || sheets.showLanguage ||
        sheets.showCaptions || sheets.showSpeed
    DisposableEffect(anySheet) {
        TvDpadInput.sheetOpen = anySheet
        onDispose { TvDpadInput.sheetOpen = false }
    }
    DisposableEffect(
        sheets.showChapters, sheets.showQuality, sheets.showLanguage, sheets.showCaptions,
        sheets.showSpeed, overlayVisible,
    ) {
        TvDpadInput.overlayDismiss = {
            when {
                sheets.showChapters -> { sheets.onShowChapters(false); true }
                sheets.showQuality -> { sheets.onShowQuality(false); true }
                sheets.showLanguage -> { sheets.onShowLanguage(false); true }
                sheets.showCaptions -> { sheets.onShowCaptions(false); true }
                sheets.showSpeed -> { sheets.onShowSpeed(false); true }
                overlayVisible -> { TvTransportState.hide(); true }
                else -> false
            }
        }
        onDispose {
            if (TvDpadInput.overlayDismiss != null) TvDpadInput.overlayDismiss = null
        }
    }
    DisposableEffect(Unit) {
        TvTransportState.reveal()
        onDispose { TvTransportState.hide() }
    }
}
