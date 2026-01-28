package com.vayunmathur.youpipe.ui

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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.youpipe.R
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    initialVideoStream: VideoStream,
    initialAudioStream: AudioStream,
    videoStreams: List<VideoStream>,
    audioStreams: List<AudioStream>,
    segments: List<VideoChapter>
) {
    val context = LocalContext.current

    // State for current selected streams
    var currentVideoStream by remember { mutableStateOf(initialVideoStream) }
    var currentAudioStream by remember { mutableStateOf(initialAudioStream) }

    // State for controls visibility and playback tracking
    var isControlsVisible by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }

    // Independent states for Quality and Chapter Dropdowns
    var isVideoMenuExpanded by remember { mutableStateOf(false) }
    var isAudioMenuExpanded by remember { mutableStateOf(false) }
    var isChapterMenuVisible by remember { mutableStateOf(false) }

    // Network setup
    val okHttpClient = remember { OkHttpClient() }
    val dataSourceFactory = remember {
        OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
    }

    // Setup ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    // Listener for position and duration updates
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    duration = player.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Polling effect for position updates
    LaunchedEffect(exoPlayer, isDragging) {
        while (true) {
            if (!isDragging) {
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    // Auto-hide controls effect
    LaunchedEffect(isControlsVisible, exoPlayer.isPlaying) {
        if (isControlsVisible && exoPlayer.isPlaying && !isVideoMenuExpanded && !isAudioMenuExpanded && !isChapterMenuVisible) {
            delay(3000)
            isControlsVisible = false
        }
    }

    // Handle Media Source switching (Maintains timestamp)
    LaunchedEffect(currentVideoStream, currentAudioStream) {
        val lastPosition = exoPlayer.currentPosition

        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(currentVideoStream.url))

        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(currentAudioStream.url))

        val mergedSource = MergingMediaSource(videoSource, audioSource)

        exoPlayer.setMediaSource(mergedSource)
        exoPlayer.prepare()
        if (lastPosition > 0L) {
            exoPlayer.seekTo(lastPosition)
        }
    }

    // Lifecycle Management
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { isControlsVisible = !isControlsVisible }
    ) {
        PlayerSurface(
            player = exoPlayer,
            modifier = Modifier.fillMaxSize(),
        )

        // Overlay with Animations
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                // Top Right: Quality and Chapter Selectors
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    // Video Quality Dropdown
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { isVideoMenuExpanded = true }
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = currentVideoStream.quality,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Icon(
                                painter = painterResource(R.drawable.outline_arrow_drop_down_24),
                                contentDescription = "Select Video Quality",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = isVideoMenuExpanded,
                            onDismissRequest = { isVideoMenuExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            Text(
                                "Video Quality",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            videoStreams.forEach { stream ->
                                DropdownMenuItem(
                                    text = { Text("${stream.quality} (${stream.fps}fps)") },
                                    onClick = {
                                        currentVideoStream = stream
                                        isVideoMenuExpanded = false
                                    },
                                    leadingIcon = {
                                        if (currentVideoStream == stream) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.outline_thumb_up_24),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Audio Quality Dropdown
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { isAudioMenuExpanded = true }
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${currentAudioStream.bitrate / 1000}kbps",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Icon(
                                painter = painterResource(R.drawable.outline_arrow_drop_down_24),
                                contentDescription = "Select Audio Quality",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = isAudioMenuExpanded,
                            onDismissRequest = { isAudioMenuExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            Text(
                                "Audio Quality",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            audioStreams.forEach { stream ->
                                DropdownMenuItem(
                                    text = { Text("${stream.bitrate / 1000} kbps") },
                                    onClick = {
                                        currentAudioStream = stream
                                        isAudioMenuExpanded = false
                                    },
                                    leadingIcon = {
                                        if (currentAudioStream == stream) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.outline_thumb_up_24),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Chapter Menu Button
                    if (segments.isNotEmpty()) {
                        IconButton(
                            onClick = { isChapterMenuVisible = true },
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.outline_list_24),
                                contentDescription = "Chapters",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Center Play/Pause
                PlayPauseButton(
                    player = exoPlayer,
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.Center),
                )

                // Bottom Controls (Seekbar + Time)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
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
                        onValueChange = {
                            isDragging = true
                            currentPosition = it.toLong()
                        },
                        onValueChangeFinished = {
                            exoPlayer.seekTo(currentPosition)
                            isDragging = false
                        },
                        valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }

        // CHAPTER OVERLAY
        AnimatedVisibility(
            visible = isChapterMenuVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .clickable(enabled = true, onClick = {}) // Block click through
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Chapter",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { isChapterMenuVisible = false }) {
                            IconClose(tint = Color.White)
                        }
                    }

                    // Chapter List
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(segments) { chapter ->
                            val isCurrent = currentPosition >= chapter.time &&
                                    (segments.getOrNull(segments.indexOf(chapter) + 1)?.time?.let { currentPosition < it } ?: true)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        exoPlayer.seekTo(chapter.time.toLong())
                                        isChapterMenuVisible = false
                                    }
                                    .background(if (isCurrent) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail
                                AsyncImage(
                                    model = chapter.previewURL,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .width(120.dp)
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.DarkGray),
                                    contentScale = ContentScale.Crop
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chapter.title,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2
                                    )
                                    Text(
                                        text = formatTime(chapter.time.toLong()),
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
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

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}