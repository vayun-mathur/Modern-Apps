package com.vayunmathur.youpipe.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.findActivity
import com.vayunmathur.youpipe.rememberIsInPipMode
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    viewModel: DatabaseViewModel,
    videoInfo: VideoInfo,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    segments: List<VideoChapter>,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    var languages by remember { mutableStateOf(audioStreams.map { it.language }.distinct().sorted()) }
    var language by remember { mutableStateOf(if("en" in languages) "en" else languages.first()) }
    val audioStreamOptions = audioStreams.filter { it.language == language }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentVideoStream by remember { mutableStateOf(videoStreams.first()) }
    var currentAudioStream by remember { mutableStateOf(audioStreamOptions.first()) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }

    var isVideoMenuExpanded by remember { mutableStateOf(false) }
    var isLanguageMenuExpanded by remember { mutableStateOf(false) }
    var isAudioMenuExpanded by remember { mutableStateOf(false) }
    var isChapterMenuVisible by remember { mutableStateOf(false) }

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

    var aspectRatio by remember { mutableStateOf(16f / 9f) }

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    duration = player.duration.coerceAtLeast(0L)
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
        while (true) {
            if (!isDragging && player.isPlaying) {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
                viewModel.upsert(HistoryVideo.fromVideoData(videoInfo.copy(duration = duration), currentPosition))
            }
            delay(300)
        }
    }

    val historyVideo by viewModel.getNullable<HistoryVideo>(videoInfo.videoID)
    val timeWatched = historyVideo?.progress ?: 0

    // 3. Updated LaunchedEffect to pass audio URI through Metadata Extras
    LaunchedEffect(controller, currentVideoStream, currentAudioStream, videoInfo.name, videoInfo.author) {
        val player = controller ?: return@LaunchedEffect

        // Pack the audio URI into the extras bundle
        val extras = Bundle().apply {
            putString("extra_audio_uri", currentAudioStream.url)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(videoInfo.name)
            .setArtist(videoInfo.author)
            .setExtras(extras) // Pass the extras bundle
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(currentVideoStream.url) // This becomes the Video source
            .setMediaMetadata(metadata)
            .build()

        player.setMediaItem(mediaItem, timeWatched)
        player.prepare()
        currentPosition = timeWatched
    }

    val isPipMode = rememberIsInPipMode()

    val modifier = if(isFullscreen) Modifier.fillMaxHeight() else Modifier.aspectRatio(16f / 9f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { isControlsVisible = !isControlsVisible }
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
                                Text(text = currentVideoStream.quality, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Icon(painter = painterResource(R.drawable.outline_arrow_drop_down_24), contentDescription = null, tint = Color.White)
                            }
                        }
                        DropdownMenu(expanded = isVideoMenuExpanded, onDismissRequest = { isVideoMenuExpanded = false }) {
                            videoStreams.forEach { stream ->
                                DropdownMenuItem(
                                    text = { Text("${stream.quality} (${stream.fps}fps)") },
                                    onClick = { currentVideoStream = stream; isVideoMenuExpanded = false }
                                )
                            }
                        }
                    }

                    Box {
                        Surface(
                            onClick = { isLanguageMenuExpanded = true },
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text = language, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Icon(painter = painterResource(R.drawable.outline_arrow_drop_down_24), contentDescription = null, tint = Color.White)
                            }
                        }
                        DropdownMenu(expanded = isLanguageMenuExpanded, onDismissRequest = { isLanguageMenuExpanded = false }) {
                            languages.forEach { stream ->
                                DropdownMenuItem(
                                    text = { Text(stream) },
                                    onClick = { language = stream; currentAudioStream = audioStreams.find { it.language == stream && it.bitrate == currentAudioStream.bitrate } ?: audioStreams.first { it.language == stream }; isAudioMenuExpanded = false }
                                )
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
                                Text(text = "${currentAudioStream.bitrate / 1000}kbps", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                Icon(painter = painterResource(R.drawable.outline_arrow_drop_down_24), contentDescription = null, tint = Color.White)
                            }
                        }
                        DropdownMenu(expanded = isAudioMenuExpanded, onDismissRequest = { isAudioMenuExpanded = false }) {
                            audioStreamOptions.forEach { stream ->
                                DropdownMenuItem(
                                    text = { Text("${stream.bitrate / 1000}kbps") },
                                    onClick = { currentAudioStream = stream; isAudioMenuExpanded = false }
                                )
                            }
                        }
                    }

                    if (segments.isNotEmpty()) {
                        IconButton(
                            onClick = { isChapterMenuVisible = true },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).size(32.dp)
                        ) {
                            Icon(painter = painterResource(R.drawable.outline_list_24), contentDescription = "Chapters", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                controller?.let { player ->
                    PlayPauseButton(
                        player = player,
                        modifier = Modifier.size(64.dp).align(Alignment.Center),
                    )
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(currentPosition),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = formatTime(duration),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
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
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    Spacer(Modifier.padding(4.dp))
                    IconButton({onFullscreenChange(!isFullscreen)}) {
                        Icon(if(isFullscreen) painterResource(R.drawable.outline_fullscreen_exit_24) else painterResource(R.drawable.outline_fullscreen_24), null)
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
                        items(segments) { chapter ->
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
                                    model = chapter.previewURL,
                                    contentDescription = null,
                                    modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color.DarkGray),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = chapter.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                    Text(text = formatTime(chapter.time.toLong()), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}