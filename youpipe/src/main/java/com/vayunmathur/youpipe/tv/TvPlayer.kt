package com.vayunmathur.youpipe.tv

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
import com.vayunmathur.youpipe.findActivity
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.selectDefaultVideoStream
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.util.PlaybackService
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.math.min

/**
 * The TV player: the same media3 `PlayerSurface` + `MediaController`/`PlaybackService` core as
 * the phone, with the touch-only gesture layer replaced.
 *
 * Gone from the phone player: `detectTapGestures` (tap toggle, double-tap ±10s, long-press 2x),
 * the 2s auto-hide timer, hover affordances, and orientation-lock fullscreen (the activity is
 * landscape-locked in the manifest). In their place: [TvTransportOverlay] (non-focusable
 * readout + focusable controls), the [TvDpadInput] key map (scrub commit on UP, volume nudge,
 * BACK dismisses overlay first), and [TvPlaybackClock] (one per-frame ticker).
 *
 * PiP is left alone — the manifest flag is harmless on TV and the phone owns the behavior.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun TvPlayer(
    ypvm: YouPipeViewModel, videoInfo: VideoInfo, videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>, subtitles: List<SubtitleTrack>, segments: List<VideoChapter>,
    startPositionMs: Long, chrome: TvPlayerChrome,
) {
    if (videoStreams.isEmpty()) return
    val context = LocalContext.current
    val messenger = rememberMessenger()
    TvPlayerKeepScreenOn(context)
    val controllerState = remember { mutableStateOf<MediaController?>(null) }
    var controller by controllerState
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var showChapters by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var showCaptions by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    val overlayVisible by TvTransportState.visible.collectAsState()
    val videoStreamState = rememberTvPlayerVideoStream(videoStreams, context)
    val audioState = rememberTvPlayerAudioState(audioStreams)
    var selectedSubtitle by remember { mutableStateOf<SubtitleTrack?>(null) }
    val transport by CastPlayback.transport.collectAsState()
    val playbackErrorMessage = stringResource(R.string.playback_error)
    val startedState = remember(videoInfo.videoID) { mutableStateOf(false) }
    TvPlayerControllerEffect(controllerState, context, playbackErrorMessage)
    val mediaArgs = TvPlayerMediaArgs(
        controller, videoStreamState.current, audioState.current, videoInfo, subtitles, startPositionMs,
    )
    TvPlayerMediaItemEffect(mediaArgs, startedState)
    TvPlayerTransportObserverEffect(controller) { cues = it }
    TvPlayerPositionLoop(ypvm, videoInfo, controller)
    TvPlayerTrackAndSpeedEffects(controller, selectedSubtitle, transport.speed)
    val sheets = TvPlayerSheetState(
        showChapters, { showChapters = it }, showQuality, { showQuality = it }, showLanguage,
        { showLanguage = it }, showCaptions, { showCaptions = it }, showSpeed, { showSpeed = it },
    )
    TvPlayerOverlayDismissEffects(sheets, overlayVisible)
    val focusRequester = remember { FocusRequester() }
    TvInitialFocus(focusRequester)
    val overlayArgs = TvPlayerOverlayArgs(
        ypvm, videoInfo, videoStreams, subtitles, segments, transport, cues, overlayVisible, controller,
        audioState.entries, audioState.language, audioState.onLanguage, selectedSubtitle,
        { selectedSubtitle = it }, videoStreamState.current, videoStreamState.onCurrent, sheets, chrome,
    )
    TvPlayerContent(controller, focusRequester, overlayArgs)
}

@Composable
private fun TvPlayerKeepScreenOn(context: Context) {
    // Keep the screen on while the player is composed — same DisposableEffect pattern
    // as the phone's VideoPlayerViewState.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private data class TvPlayerVideoStreamState(
    val current: VideoStream,
    val onCurrent: (VideoStream) -> Unit,
)

@Composable
private fun rememberTvPlayerVideoStream(
    videoStreams: List<VideoStream>,
    context: Context,
): TvPlayerVideoStreamState {
    val deviceHeightPx = remember(context) {
        context.resources.displayMetrics.let { min(it.widthPixels, it.heightPixels) }
    }
    var currentVideoStream by remember(videoStreams) {
        mutableStateOf(selectDefaultVideoStream(videoStreams, deviceHeightPx) ?: videoStreams.first())
    }
    return TvPlayerVideoStreamState(currentVideoStream) { currentVideoStream = it }
}

private data class TvPlayerAudioState(
    val entries: List<Pair<String, String>>,
    val language: String,
    val onLanguage: (String) -> Unit,
    val current: AudioStream?,
)

@Composable
private fun rememberTvPlayerAudioState(audioStreams: List<AudioStream>): TvPlayerAudioState {
    // TV keeps the phone's audio rule: highest-quality opus for the selected language.
    // Use displayName for language UI when available, fallback to language code (same as phone).
    val languageEntries = remember(audioStreams) {
        audioStreams.map { it.language to (it.displayName ?: it.language) }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    val audioLanguages = languageEntries.map { it.first }
    var language by remember(audioStreams) {
        mutableStateOf(
            if ("en" in audioLanguages) "en" else audioLanguages.firstOrNull().orEmpty(),
        )
    }
    // Keep language valid when audioStreams change (new video).
    LaunchedEffect(audioLanguages) {
        if (language !in audioLanguages) {
            language = if ("en" in audioLanguages) "en" else audioLanguages.firstOrNull().orEmpty()
        }
    }
    val currentAudioStream = remember(audioStreams, language) {
        audioStreams.filter { it.language == language }.maxByOrNull { it.bitrate }
    }
    return TvPlayerAudioState(languageEntries, language, { language = it }, currentAudioStream)
}

@Composable
private fun TvPlayerControllerEffect(
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

private data class TvPlayerMediaArgs(
    val controller: MediaController?,
    val videoStream: VideoStream,
    val audioStream: AudioStream?,
    val videoInfo: VideoInfo,
    val subtitles: List<SubtitleTrack>,
    val startPositionMs: Long,
)

@Composable
private fun TvPlayerMediaItemEffect(args: TvPlayerMediaArgs, startedState: MutableState<Boolean>) {
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
private fun TvPlayerTransportObserverEffect(controller: MediaController?, onCues: (List<Cue>) -> Unit) {
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
private fun TvPlayerTrackAndSpeedEffects(
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

private data class TvPlayerSheetState(
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

@Composable
private fun TvPlayerOverlayDismissEffects(sheets: TvPlayerSheetState, overlayVisible: Boolean) {
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

private data class TvPlayerOverlayArgs(
    val ypvm: YouPipeViewModel,
    val videoInfo: VideoInfo,
    val videoStreams: List<VideoStream>,
    val subtitles: List<SubtitleTrack>,
    val segments: List<VideoChapter>,
    val transport: CastPlayback.Transport,
    val cues: List<Cue>,
    val overlayVisible: Boolean,
    val controller: MediaController?,
    val languageEntries: List<Pair<String, String>>,
    val language: String,
    val onLanguage: (String) -> Unit,
    val selectedSubtitle: SubtitleTrack?,
    val onSubtitle: (SubtitleTrack?) -> Unit,
    val currentVideoStream: VideoStream,
    val onVideoStream: (VideoStream) -> Unit,
    val sheets: TvPlayerSheetState,
    val chrome: TvPlayerChrome,
)

@OptIn(UnstableApi::class)
@Composable
private fun TvPlayerSurface(controller: MediaController?, focusRequester: FocusRequester) {
    controller?.let { player ->
        PlayerSurface(
            player = player,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
        )
    } ?: Box(Modifier.fillMaxSize().background(Color.Black))
}

@Composable
private fun TvPlayerContent(
    controller: MediaController?,
    focusRequester: FocusRequester,
    args: TvPlayerOverlayArgs,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        TvPlayerSurface(controller, focusRequester)
        TvPlayerOverlays(
            ypvm = args.ypvm, videoInfo = args.videoInfo, videoStreams = args.videoStreams,
            subtitles = args.subtitles, segments = args.segments, transport = args.transport,
            cues = args.cues, overlayVisible = args.overlayVisible, controller = args.controller,
            languageEntries = args.languageEntries, language = args.language, onLanguage = args.onLanguage,
            selectedSubtitle = args.selectedSubtitle, onSubtitle = args.onSubtitle,
            currentVideoStream = args.currentVideoStream, onVideoStream = args.onVideoStream,
            showChapters = args.sheets.showChapters, onShowChapters = args.sheets.onShowChapters,
            showQuality = args.sheets.showQuality, onShowQuality = args.sheets.onShowQuality,
            showLanguage = args.sheets.showLanguage, onShowLanguage = args.sheets.onShowLanguage,
            showCaptions = args.sheets.showCaptions, onShowCaptions = args.sheets.onShowCaptions,
            showSpeed = args.sheets.showSpeed, onShowSpeed = args.sheets.onShowSpeed, chrome = args.chrome,
        )
    }
}
