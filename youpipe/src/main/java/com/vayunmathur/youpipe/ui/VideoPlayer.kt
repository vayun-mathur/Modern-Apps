package com.vayunmathur.youpipe.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconFullscreen
import com.vayunmathur.library.ui.IconFullscreenExit
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SliderDefaults
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectTapGestures
import com.vayunmathur.library.ui.CircularProgressIndicator
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.findActivity
import com.vayunmathur.youpipe.rememberIsInPipMode
import com.vayunmathur.youpipe.util.PlaybackService
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

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
    val context = LocalContext.current
    val sponsorBlockEnabled by ypvm.sponsorBlockEnabled.collectAsState()
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val videoState by ypvm.videoState.collectAsState()
    val sponsorSegments = videoState.sponsorSegments.filter { it.category in sponsorBlockCategories }

    if (videoStreams.isEmpty()) return

    val hasAudio = audioStreams.isNotEmpty()
    val languages = remember(audioStreams) { audioStreams.map { it.language }.distinct().sorted() }
    var language by remember { mutableStateOf(if("en" in languages) "en" else languages.firstOrNull() ?: "") }
    val audioStreamOptions = audioStreams.filter { it.language == language }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentVideoStream by remember { mutableStateOf(videoStreams.first()) }
    var currentAudioStream by remember { mutableStateOf(audioStreamOptions.firstOrNull()) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }

    var isVideoMenuExpanded by remember { mutableStateOf(false) }
    var isLanguageMenuExpanded by remember { mutableStateOf(false) }
    var isAudioMenuExpanded by remember { mutableStateOf(false) }
    var isChapterMenuVisible by remember { mutableStateOf(false) }
    var isCaptionMenuExpanded by remember { mutableStateOf(false) }

    var selectedSubtitle by remember { mutableStateOf<SubtitleTrack?>(null) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }

    DisposableEffect(Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            controller = controllerFuture.get()
        }, MoreExecutors.directExecutor())

        onDispose {
            controller?.let {
                it.stop() // This is critical!
                it.release()
            }
            MediaController.releaseFuture(controllerFuture)
        }
    }

    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val activity = context.findActivity()
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    duration = player.duration.coerceAtLeast(0L)
                    isBuffering = player.playbackState == Player.STATE_BUFFERING
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                context.findActivity().setPictureInPictureParams(PictureInPictureParams.Builder().apply {
                    setAutoEnterEnabled(isPlaying)
                }.build())
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if(videoSize.width == 0 || videoSize.height == 0) return
                aspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat() * videoSize.pixelWidthHeightRatio
            }
            override fun onCues(cueGroup: CueGroup) {
                android.util.Log.d("YouPipeSubs", "onCues called with ${cueGroup.cues.size} cues")
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            context.findActivity().setPictureInPictureParams(PictureInPictureParams.Builder().apply {
                setAutoEnterEnabled(false)
            }.build())
        }
    }

    LaunchedEffect(controller, isDragging) {
        val player = controller ?: return@LaunchedEffect
        var lastHistoryUpsert = 0L
        while (true) {
            if (!isDragging) {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
                bufferedPosition = player.bufferedPosition.coerceAtLeast(0L)

                if (sponsorBlockEnabled) {
                    val currentSegment = sponsorSegments.find { currentPosition in it.start until it.end }
                    if (currentSegment != null) {
                        player.seekTo(currentSegment.end)
                        currentPosition = currentSegment.end
                    }
                }

                if (player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastHistoryUpsert >= HISTORY_UPSERT_INTERVAL_MS) {
                        ypvm.upsertHistoryVideo(HistoryVideo.fromVideoData(videoInfo.copy(duration = duration), currentPosition))
                        lastHistoryUpsert = now
                    }
                }
            }
            delay(300)
        }
    }

    // Persist the final watch position once when leaving the player.
    DisposableEffect(videoInfo.videoID) {
        onDispose {
            ypvm.upsertHistoryVideo(HistoryVideo.fromVideoData(videoInfo.copy(duration = duration), currentPosition))
        }
    }

    val historyFlow = remember(videoInfo.videoID) { ypvm.historyById(videoInfo.videoID) }
    val historyVideo by historyFlow.collectAsState(initial = null)
    val timeWatched = historyVideo?.progress ?: 0

    // 3. Updated LaunchedEffect to pass audio URI through Metadata Extras
    LaunchedEffect(controller, currentVideoStream, currentAudioStream, videoInfo.name, videoInfo.author, subtitles) {
        val player = controller ?: return@LaunchedEffect

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(videoInfo.name)
            .setArtist(videoInfo.author)

        currentAudioStream?.let { audio ->
            val extras = Bundle().apply {
                putString("extra_audio_uri", audio.url)
            }
            metadataBuilder.setExtras(extras)
        }

        android.util.Log.d("YouPipeSubs", "Building ${subtitles.size} subtitle configs")
        val subtitleConfigs = subtitles.map { sub ->
            android.util.Log.d("YouPipeSubs", "Sub: lang=${sub.languageTag} mime=${sub.mimeType} url=${sub.url} auto=${sub.autoGenerated}")
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
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
        currentPosition = timeWatched
    }

    LaunchedEffect(controller, selectedSubtitle) {
        val player = controller ?: return@LaunchedEffect
        android.util.Log.d("YouPipeSubs", "Selected subtitle changed: ${selectedSubtitle?.languageTag} url=${selectedSubtitle?.url}")
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

    val isPipMode = rememberIsInPipMode()
    val scope = rememberCoroutineScope()

    val modifier = if(isFullscreen) Modifier.fillMaxHeight() else Modifier.aspectRatio(16f / 9f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible },
                    onDoubleTap = { offset ->
                        val isRightSide = offset.x > size.width / 2
                        if (isRightSide) {
                            controller?.seekTo(currentPosition + 10000L)
                        } else {
                            controller?.seekTo(currentPosition - 10000L)
                        }
                    },
                    onPress = {
                        val job = scope.launch {
                            delay(viewConfiguration.longPressTimeoutMillis)
                            controller?.setPlaybackSpeed(2f)
                        }
                        try {
                            awaitRelease()
                        } finally {
                            job.cancel()
                            controller?.setPlaybackSpeed(1f)
                        }
                    }
                )
            }
    ) {
        val playerModifier = if (aspectRatio > 16f/9f) {
            // Video is wider than container -> match width, height will follow ratio
            Modifier.fillMaxWidth().aspectRatio(aspectRatio)
        } else {
            // Video is taller than container -> match height, width will follow ratio
            Modifier.fillMaxHeight().aspectRatio(aspectRatio)
        }
        controller?.let { player ->
            PlayerSurface(
                player = player,
                modifier = playerModifier.align(Alignment.Center),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW
            )
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

        AnimatedVisibility(visible = isControlsVisible && !isPipMode, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {

                Row(
                    modifier = Modifier.padding(16.dp).align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Surface(
                            onClick = { isVideoMenuExpanded = true },
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(R.string.video_quality_codec, currentVideoStream.quality, getVideoCodecName(currentVideoStream.codec)), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                IconArrowDropDown(tint = Color.White)
                            }
                        }
                        DropdownMenu(expanded = isVideoMenuExpanded, onDismissRequest = { isVideoMenuExpanded = false }) {
                            videoStreams.forEach { stream ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.video_quality_fps_codec, stream.quality, stream.fps, getVideoCodecName(stream.codec))) },
                                    onClick = { currentVideoStream = stream; isVideoMenuExpanded = false }
                                )
                            }
                        }
                    }

                    if (hasAudio) {
                        if (languages.size > 1) {
                            Box {
                                Surface(
                                    onClick = { isLanguageMenuExpanded = true },
                                    color = Color.Black.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = language, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                        IconArrowDropDown(tint = Color.White)
                                    }
                                }
                                DropdownMenu(expanded = isLanguageMenuExpanded, onDismissRequest = { isLanguageMenuExpanded = false }) {
                                    languages.forEach { stream ->
                                        DropdownMenuItem(
                                            text = { Text(stream) },
                                            onClick = { language = stream; currentAudioStream = audioStreams.find { it.language == stream && it.bitrate == currentAudioStream?.bitrate } ?: audioStreams.first { it.language == stream }; isLanguageMenuExpanded = false }
                                        )
                                    }
                                }
                            }
                        }

                        Box {
                            Surface(
                                onClick = { isAudioMenuExpanded = true },
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = stringResource(R.string.audio_bitrate_codec, (currentAudioStream?.bitrate ?: 0) / 1000, getAudioCodecName(currentAudioStream?.codec ?: "")), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    IconArrowDropDown(tint = Color.White)
                                }
                            }
                        DropdownMenu(expanded = isAudioMenuExpanded, onDismissRequest = { isAudioMenuExpanded = false }) {
                                audioStreamOptions.forEach { stream ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.audio_bitrate_codec, stream.bitrate / 1000, getAudioCodecName(stream.codec))) },
                                        onClick = { currentAudioStream = stream; isAudioMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }

                    if (subtitles.isNotEmpty()) {
                        Box {
                            Surface(
                                onClick = { isCaptionMenuExpanded = true },
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = selectedSubtitle?.languageTag?.ifEmpty { "CC" } ?: "CC", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    IconArrowDropDown(tint = Color.White)
                                }
                            }
                            DropdownMenu(expanded = isCaptionMenuExpanded, onDismissRequest = { isCaptionMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Off") },
                                    onClick = { selectedSubtitle = null; isCaptionMenuExpanded = false }
                                )
                                subtitles.forEach { sub ->
                                    DropdownMenuItem(
                                        text = { Text(sub.displayName) },
                                        onClick = { selectedSubtitle = sub; isCaptionMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }

                    if (segments.isNotEmpty()) {
                        IconButton(
                            onClick = { isChapterMenuVisible = true },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).size(32.dp)
                        ) {
                            IconList(tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }

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
                Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = DateUtils.formatElapsedTime(currentPosition / 1000),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = DateUtils.formatElapsedTime(duration / 1000),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Box(contentAlignment = Alignment.CenterStart) {
                            Slider(
                                value = if (duration > 0) bufferedPosition.toFloat() else 0f,
                                onValueChange = { },
                                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Transparent,
                                    activeTrackColor = Color.White.copy(alpha = 0.3f),
                                    inactiveTrackColor = Color.Transparent
                                )
                            )
                            if (duration > 0) {
                                Canvas(modifier = Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 20.dp)) {
                                    sponsorSegments.forEach { segment ->
                                        val startX = (segment.start.toFloat() / duration) * size.width
                                        val endX = (segment.end.toFloat() / duration) * size.width
                                        drawRect(
                                            color = Color.Yellow.copy(alpha = 0.7f),
                                            topLeft = Offset(startX, 0f),
                                            size = Size(endX - startX, size.height)
                                        )
                                    }
                                }
                            }
                            Slider(
                                value = if (duration > 0) currentPosition.toFloat() else 0f,
                                onValueChange = { isDragging = true; currentPosition = it.toLong() },
                                onValueChangeFinished = {
                                    controller?.seekTo(currentPosition); isDragging = false
                                },
                                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.padding(4.dp))
                    IconButton({onFullscreenChange(!isFullscreen)}) {
                        if(isFullscreen) IconFullscreenExit() else IconFullscreen()
                    }
                }
            }
        }

        AnimatedVisibility(visible = isChapterMenuVisible && !isPipMode, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).clickable(enabled = true, onClick = {})) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Chapter", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { isChapterMenuVisible = false }) {
                            IconClose(tint = Color.White)
                        }
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(segments, key = { it.time }) { chapter ->
                            val isCurrent = currentPosition >= chapter.time &&
                                    (segments.getOrNull(segments.indexOf(chapter) + 1)?.time?.let { currentPosition < it } ?: true)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        controller?.seekTo(chapter.time.toLong())
                                        isChapterMenuVisible = false
                                    }
                                    .background(if (isCurrent) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(chapter.previewURL)
                                        .memoryCacheKey("chapter-${chapter.time}")
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color.DarkGray),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = chapter.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                    Text(text = DateUtils.formatElapsedTime(chapter.time.toLong() / 1000), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val HISTORY_UPSERT_INTERVAL_MS = 5000L
