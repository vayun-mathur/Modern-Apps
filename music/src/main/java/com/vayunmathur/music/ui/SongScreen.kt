package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.navigation3.runtime.NavBackStack
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.music.PlaybackManager
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.database.Music
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongScreen(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, songID: Long) {
    val song by viewModel.get<Music>(songID)
    val context = LocalContext.current
    val playbackManager = remember { PlaybackManager.getInstance(context) }

    // Playback States
    val isPlaying by playbackManager.isPlaying.collectAsState()
    val currentPos by playbackManager.currentPosition.collectAsState()
    val duration by playbackManager.duration.collectAsState()
    val shuffleMode by playbackManager.shuffleMode.collectAsState()
    val repeatMode by playbackManager.repeatMode.collectAsState()

    LaunchedEffect(Unit) {
        playbackManager.playSong(song)
    }

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("Now Playing", style = MaterialTheme.typography.labelLarge) },
                navigationIcon = {
                    IconNavigation(backStack)
                },
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
            // Album Art
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(12.dp)
            ) {
                AsyncImage(
                    model = song.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Song Info
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
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
                // Repeat
                IconButton(onClick = { playbackManager.toggleRepeat() }) {
                    Icon(
                        painter = painterResource(if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                        contentDescription = null,
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Main Playback Row
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
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                            null, Modifier.size(42.dp), tint = Color.White
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { playbackManager.skipNext() }) {
                        Icon(painter = painterResource(id = R.drawable.ic_skip_next), null, Modifier.size(40.dp), tint = Color.White)
                    }
                }

                // Shuffle
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

private fun formatTime(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / 1000) / 60
    return "%d:%02d".format(min, sec)
}
