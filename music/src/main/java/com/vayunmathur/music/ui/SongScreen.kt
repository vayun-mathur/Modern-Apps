package com.vayunmathur.music.ui

import android.content.Context
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.AlbumArt
import com.vayunmathur.music.PlaybackManager
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream

// Data class to hold parsed lyric lines
data class LyricLine(val timestamp: Long, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongScreen(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }
    val currentlyPlaying by playbackManager.currentMediaItem.collectAsState()
    val song = currentlyPlaying ?: return

    // Playback States
    val isPlaying by playbackManager.isPlaying.collectAsState()
    val currentPos by playbackManager.currentPosition.collectAsState()
    val duration by playbackManager.duration.collectAsState()
    val shuffleMode by playbackManager.shuffleMode.collectAsState()
    val repeatMode by playbackManager.repeatMode.collectAsState()

    // UI States
    var showLyrics by remember { mutableStateOf(false) }
    var rawLyrics by remember { mutableStateOf("") }

    // Parse lyrics whenever rawLyrics changes
    val parsedLyrics = remember(rawLyrics) { parseLyrics(rawLyrics) }

    // Find the current active lyric index based on currentPos
    val currentLyricIndex = remember(parsedLyrics, currentPos) {
        parsedLyrics.indexOfLast { it.timestamp <= currentPos }
    }

    LaunchedEffect(currentlyPlaying) {
        if (currentlyPlaying != null) {
            // Note: Ensure artworkUri is the correct URI for the audio file containing tags
            val lyrics = getLyricsFromContentUri(context, currentlyPlaying!!.mediaMetadata.artworkUri!!)
            rawLyrics = lyrics ?: ""
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("Now Playing", style = MaterialTheme.typography.labelLarge) },
                navigationIcon = { IconNavigation(backStack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Toggleable Album Art / Lyrics Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { showLyrics = !showLyrics }
            ) {
                Crossfade(targetState = showLyrics, label = "LyricsToggle") { isShowingLyrics ->
                    if (isShowingLyrics) {
                        LyricsView(parsedLyrics, currentLyricIndex)
                    } else {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(12.dp)
                        ) {
                            AlbumArt(song.mediaMetadata.artworkUri!!, Modifier.fillMaxSize())
                        }
                    }
                }
            }

            // Song Info
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.mediaMetadata.title.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.mediaMetadata.artist.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Gray
                    )
                }
                IconButton(onClick = {}) {
                    Icon(painter = painterResource(id = R.drawable.ic_more_vert), null, tint = Color.White)
                }
            }

            // Progress Slider
            Column {
                Slider(
                    value = if (duration > 0) currentPos.toFloat() / duration.toFloat() else 0f,
                    onValueChange = { playbackManager.seekTo((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(0.2f)
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(currentPos), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Text(formatTime(duration), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Controls
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { playbackManager.toggleRepeat() }) {
                    Icon(
                        painter = painterResource(if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                        contentDescription = null,
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { playbackManager.skipPrevious() }) {
                        Icon(painter = painterResource(id = R.drawable.ic_skip_previous), null, Modifier.size(40.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(0.1f))
                            .clickable { playbackManager.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        if(isPlaying) {
                            IconPause()
                        } else {
                            IconPlay()
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { playbackManager.skipNext() }) {
                        Icon(painter = painterResource(id = R.drawable.ic_skip_next), null, Modifier.size(40.dp), tint = Color.White)
                    }
                }

                IconButton(onClick = { playbackManager.toggleShuffle() }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shuffle), null,
                        tint = if (shuffleMode) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun LyricsView(lyrics: List<LyricLine>, currentIndex: Int) {
    val listState = rememberLazyListState()

    // Auto-scroll to current lyric
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(16.dp)
    ) {
        if (lyrics.isEmpty()) {
            Text(
                "No lyrics available",
                color = Color.Gray,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 40.dp)
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val isCurrent = index == currentIndex
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            fontSize = if (isCurrent) 22.sp else 18.sp
                        ),
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

fun parseLyrics(lrcContent: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    // Regex to match [mm:ss.xx] text
    val lyricPattern = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

    lrcContent.lines().forEach { line ->
        val match = lyricPattern.find(line)
        if (match != null) {
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val ms = match.groupValues[3].toLong()
            val text = match.groupValues[4].trim()

            // Convert to total milliseconds
            val timestamp = (min * 60 * 1000) + (sec * 1000) + (if (match.groupValues[3].length == 2) ms * 10 else ms)
            if (text.isNotEmpty()) {
                lines.add(LyricLine(timestamp, text))
            }
        }
    }
    return lines.sortedBy { it.timestamp }
}

private fun formatTime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / 1000) / 60
    return "%d:%02d".format(min, sec)
}

fun getLyricsFromContentUri(context: Context, contentUri: Uri): String? {
    var tempFile: File? = null
    try {
        tempFile = File.createTempFile("temp_music", ".m4a", context.getCacheDir())
        context.contentResolver.openInputStream(contentUri).use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while ((inputStream!!.read(buffer).also { bytesRead = it }) != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        }
        val f = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
        val tag = f.tag
        return tag.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS)
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    } finally {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete()
        }
    }
}