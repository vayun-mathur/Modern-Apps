package com.vayunmathur.youpipe.ui

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.os.Build
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
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toRect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.findActivity
import com.vayunmathur.youpipe.rememberIsInPipMode
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    initialVideoStream: VideoStream,
    initialAudioStream: AudioStream,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    segments: List<VideoChapter>,
    videoTitle: String = "Video Title",
    uploaderName: String = "Uploader"
) {
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentVideoStream by remember { mutableStateOf(initialVideoStream) }
    var currentAudioStream by remember { mutableStateOf(initialAudioStream) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }

    var isVideoMenuExpanded by remember { mutableStateOf(false) }
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
            if (!isDragging) {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    // 3. Updated LaunchedEffect to pass audio URI through Metadata Extras
    LaunchedEffect(controller, currentVideoStream, currentAudioStream, videoTitle, uploaderName) {
        val player = controller ?: return@LaunchedEffect
        val lastPosition = player.currentPosition

        // Pack the audio URI into the extras bundle
        val extras = Bundle().apply {
            putString("extra_audio_uri", currentAudioStream.url)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(videoTitle)
            .setArtist(uploaderName)
            .setExtras(extras) // Pass the extras bundle
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(currentVideoStream.url) // This becomes the Video source
            .setMediaMetadata(metadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        if (lastPosition > 0L) {
            player.seekTo(lastPosition)
        }
    }

    val isPipMode = rememberIsInPipMode()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { isControlsVisible = !isControlsVisible }
    ) {
        controller?.let { player ->
            PlayerSurface(
                player = player,
                modifier = Modifier.fillMaxSize(),
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
                            audioStreams.forEach { stream ->
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

                Column(modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = formatTime(currentPosition), color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text(text = formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() else 0f,
                        onValueChange = { isDragging = true; currentPosition = it.toLong() },
                        onValueChangeFinished = { controller?.seekTo(currentPosition); isDragging = false },
                        valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                    )
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