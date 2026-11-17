package com.vayunmathur.youpipe.ui

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.rememberIsInPipMode
import com.vayunmathur.youpipe.network.SponsorSegment
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import kotlin.math.min
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/**
 * Default stream for #826: the best resolution at or below the device's native
 * resolution (landscape height = the smaller screen edge), so a 1080p phone
 * does not stream 4K by default and burn bandwidth. The quality menu still
 * lists every stream, so picking higher remains one tap away.
 *
 * Pure so it is unit-testable. Falls back to the lowest stream when everything
 * exceeds the cap (never empty when input is non-empty), and to the highest
 * when the device height is unknown.
 */
internal fun selectDefaultVideoStream(
    videoStreams: List<VideoStream>,
    deviceHeightPx: Int,
): VideoStream? {
    if (videoStreams.isEmpty()) return null
    if (deviceHeightPx <= 0) return videoStreams.first()
    return videoStreams.filter { it.height in 1..deviceHeightPx }
        .maxByOrNull { it.height }
        ?: videoStreams.minByOrNull { it.height }
}

/**
 * Playback-owned state for [VideoPlayer]: audio/language selection, the media controller
 * lifecycle, transport observers, history writes, the media item, subtitles and tempo.
 *
 * Moved from VideoPlayer.kt (FileLength split); behavior identical. The view-owned half
 * (menus, lock, hover, PiP, aspect ratio, cast session, controls timer) lives in
 * [VideoPlayerViewState]; the player UI reads both via `with(pb) { with(vs) { ... } }`.
 * The controller/transport/history/media effects live in VideoPlayerPlaybackEffects.kt.
 */
internal class VideoPlayerPlaybackState(
    languageState: androidx.compose.runtime.MutableState<String>,
    controllerState: androidx.compose.runtime.MutableState<MediaController?>,
    currentVideoStreamState: androidx.compose.runtime.MutableState<VideoStream>,
    currentAudioStreamState: androidx.compose.runtime.MutableState<AudioStream?>,
    historyPositionState: androidx.compose.runtime.MutableLongState,
    historyDurationState: androidx.compose.runtime.MutableLongState,
    playbackPitchState: androidx.compose.runtime.MutableFloatState,
    unhookPitchState: androidx.compose.runtime.MutableState<Boolean>,
    selectedSubtitleState: androidx.compose.runtime.MutableState<SubtitleTrack?>,
    cuesState: androidx.compose.runtime.MutableState<List<Cue>>,
    aspectRatioState: androidx.compose.runtime.MutableFloatState,
    val languageEntries: List<Pair<String, String>>,
    val languages: List<String>,
    val audioStreamOptions: List<AudioStream>,
    val currentPosition: Long,
    val bufferedPosition: Long,
    val duration: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isDragging: Boolean,
    val playbackSpeed: Float,
    val timeWatched: Long,
    val sponsorSegments: List<SponsorSegment>,
) {
    var language: String by languageState
    var controller: MediaController? by controllerState
    var currentVideoStream: VideoStream by currentVideoStreamState
    var currentAudioStream: AudioStream? by currentAudioStreamState
    var historyPosition: Long by historyPositionState
    var historyDuration: Long by historyDurationState
    var playbackPitch: Float by playbackPitchState
    var unhookPitch: Boolean by unhookPitchState
    var selectedSubtitle: SubtitleTrack? by selectedSubtitleState
    var cues: List<Cue> by cuesState
    var aspectRatio: Float by aspectRatioState
}

@OptIn(UnstableApi::class)
@Composable
internal fun rememberVideoPlayerPlaybackState(
    ypvm: YouPipeViewModel,
    videoInfo: VideoInfo,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    subtitles: List<SubtitleTrack>,
): VideoPlayerPlaybackState {
    val sponsorBlockEnabled by ypvm.sponsorBlockEnabled.collectAsState()
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val videoState by ypvm.videoState.collectAsState()
    val sponsorSegments = videoState.sponsorSegments.filter { it.category in sponsorBlockCategories }

    // Use displayName for language UI when available (audioTrackName like "English", "Original"), fallback to language code
    val languageEntries = remember(audioStreams) {
        audioStreams.map { it.language to (it.displayName ?: it.language) }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }
    val languages = languageEntries.map { it.first }
    val languageState = remember(audioStreams) { mutableStateOf(if ("en" in languages) "en" else languages.firstOrNull() ?: "") }
    var language by languageState
    // Keep language valid when audioStreams change (new video)
    LaunchedEffect(languages) {
        if (language !in languages) {
            language = if ("en" in languages) "en" else languages.firstOrNull() ?: ""
        }
    }
    // Highest quality opus per language (sorted desc bitrate in VM), so first is best.
    val audioStreamOptions = remember(audioStreams, language) {
        audioStreams.filter { it.language == language }
    }

    val controllerState = remember { mutableStateOf<MediaController?>(null) }
    var controller by controllerState
    // #826: default to the best stream at or below the device's native resolution
    // (smaller screen edge = landscape height) instead of the absolute highest,
    // so a 1080p phone does not stream 4K by default. Keyed on the stream list
    // only: reseeds per video, never clobbers a manual pick mid-playback.
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val deviceHeightPx = remember(windowInfo) {
        min(windowInfo.containerSize.width, windowInfo.containerSize.height)
    }
    val currentVideoStreamState = remember(videoStreams) {
        mutableStateOf(selectDefaultVideoStream(videoStreams, deviceHeightPx) ?: videoStreams.first())
    }
    var currentVideoStream by currentVideoStreamState
    // Always highest quality audio for selected language (user request: remove bitrate chooser)
    val currentAudioStreamState = remember(audioStreamOptions) { mutableStateOf(audioStreamOptions.maxByOrNull { it.bitrate }) }
    var currentAudioStream by currentAudioStreamState
    // Ensure currentAudioStream stays consistent with language filter = best quality
    LaunchedEffect(language) {
        val filtered = audioStreams.filter { it.language == language }
        currentAudioStream = filtered.maxByOrNull { it.bitrate } ?: filtered.firstOrNull()
    }

    // ---- transport state, which lives in CastPlayback rather than here ----
    // All of this used to be `remember`ed locals, which was right while the only reader was the seek bar
    // drawn beside them. A television showing this video is a second reader with a different lifetime -
    // it needs to know where playback is in order to draw a bar of its own - and a `remember` dies on
    // navigation while a cast session does not. The 300 ms loop below is still the only writer of the
    // position, so `dragging` remains a sufficient guard against two seeks fighting.
    val transport by CastPlayback.transport.collectAsState()
    val currentPosition = transport.positionMs
    val bufferedPosition = transport.bufferedMs
    val duration = transport.durationMs
    val isPlaying = transport.playing
    val isBuffering = transport.buffering
    val isDragging = transport.dragging
    val playbackSpeed = transport.speed

    // Position and duration for *this* video's history entry, kept per screen rather than read back out
    // of the shared transport state. A video-to-video navigation composes both screens at once, and
    // both see the same singleton - so a shared read would persist the incoming video's progress
    // against the outgoing video's id and lose the user's place in it.
    val historyPositionState = remember(videoInfo.videoID) { mutableLongStateOf(0L) }
    val historyDurationState = remember(videoInfo.videoID) { mutableLongStateOf(0L) }

    // Tempo (playback speed) and pitch, applied via PlaybackParameters. When pitch is "hooked"
    // to tempo it tracks it 1:1 (natural resample); unhooking lets the user set it independently.
    // The tempo itself is in `transport` above, because the TV shows it and can change it.
    val playbackPitchState = remember { mutableFloatStateOf(1f) }
    var playbackPitch by playbackPitchState
    val unhookPitchState = remember { mutableStateOf(false) }
    var unhookPitch by unhookPitchState

    // Hooked pitch follows tempo from *any* source, which is why it is an effect rather than a line in
    // the slider's callback: the television can change the tempo too, and a pitch left behind would
    // silently unhook itself without the checkbox moving.
    LaunchedEffect(playbackSpeed, unhookPitch) {
        if (!unhookPitch) playbackPitch = playbackSpeed
    }

    // **Seeded once, then written back one way only.** Following the stored value would mean an echo of
    // an earlier write arriving mid-drag and snapping the slider out from under the thumb, because
    // DataStore answers asynchronously and this cannot tell an echo from a fresh value. `drop(1)` is
    // what waits for storage rather than for the flow's own 1x placeholder; if nothing is stored it
    // never arrives and 1x stands, which is the right answer anyway.
    LaunchedEffect(Unit) {
        val stored = ypvm.playbackSpeed.drop(1).first()
        CastPlayback.update { it.copy(speed = stored) }
    }
    LaunchedEffect(playbackSpeed) {
        ypvm.setPlaybackSpeed(playbackSpeed)
    }

    val selectedSubtitleState = remember { mutableStateOf<SubtitleTrack?>(null) }
    var selectedSubtitle by selectedSubtitleState
    val cuesState = remember { mutableStateOf<List<Cue>>(emptyList()) }

    // Track if we are in PiP to avoid stopping playback on dispose when PiP is active or
    // when PiP is closed and we want to keep audio-only.
    val isPipModeForLifecycle = rememberIsInPipMode()
    val wasInPipModeState = remember { mutableStateOf(false) }
    var wasInPipMode by wasInPipModeState
    LaunchedEffect(isPipModeForLifecycle) {
        if (isPipModeForLifecycle) wasInPipMode = true
    }

    VideoPlayerControllerLifecycle(controllerState, wasInPipModeState)

    val aspectRatioState = remember { mutableFloatStateOf(16f / 9f) }

    VideoPlayerTransportObserver(controller, aspectRatioState, cuesState)
    VideoPlayerPositionLoop(
        ypvm, videoInfo, controller, isDragging, sponsorBlockEnabled, sponsorSegments,
        historyPositionState, historyDurationState,
    )
    VideoPlayerHistoryPersist(ypvm, videoInfo, historyPositionState, historyDurationState)

    val historyFlow = remember(videoInfo.videoID) { ypvm.historyById(videoInfo.videoID) }
    val historyVideo by historyFlow.collectAsState(initial = null)
    val timeWatched = historyVideo?.progress ?: 0

    VideoPlayerMediaItemEffect(controller, currentVideoStream, currentAudioStream, videoInfo, subtitles, timeWatched)
    VideoPlayerSubtitleEffect(controller, selectedSubtitle)
    VideoPlayerTempoEffect(controller, playbackSpeed, playbackPitch)

    return VideoPlayerPlaybackState(
        languageState = languageState,
        controllerState = controllerState,
        currentVideoStreamState = currentVideoStreamState,
        currentAudioStreamState = currentAudioStreamState,
        historyPositionState = historyPositionState,
        historyDurationState = historyDurationState,
        playbackPitchState = playbackPitchState,
        unhookPitchState = unhookPitchState,
        selectedSubtitleState = selectedSubtitleState,
        cuesState = cuesState,
        aspectRatioState = aspectRatioState,
        languageEntries = languageEntries,
        languages = languages,
        audioStreamOptions = audioStreamOptions,
        currentPosition = currentPosition,
        bufferedPosition = bufferedPosition,
        duration = duration,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        isDragging = isDragging,
        playbackSpeed = playbackSpeed,
        timeWatched = timeWatched,
        sponsorSegments = sponsorSegments,
    )
}
