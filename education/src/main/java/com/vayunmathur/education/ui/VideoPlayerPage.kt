package com.vayunmathur.education.ui

import android.text.format.DateUtils
import androidx.compose.runtime.setValue
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import com.vayunmathur.education.Route
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.vayunmathur.education.R

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerPage(
    backStack: NavBackStack<Route>,
    videoUrl: String,
    title: String,
) {
    var failed by remember(videoUrl) { mutableStateOf(false) }
    var retryCount by remember(videoUrl) { mutableIntStateOf(0) }

    AppScaffold(
        title = { Text(title, maxLines = 1) },
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (failed) {
                VideoError { failed = false; retryCount++ }
            } else {
                VideoSurface(videoUrl, retryCount, onError = { failed = true })
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(videoUrl: String, retryCount: Int, onError: () -> Unit) {
    val context = LocalContext.current
    // Keyed by retryCount as well as the URL: the DisposableEffect below is
    // keyed by both and releases the player when its keys change, so a retry
    // must build a fresh player — reusing the released one throws.
    val player = remember(videoUrl, retryCount) { ExoPlayer.Builder(context).build() }
    var buffering by remember(videoUrl, retryCount) { mutableStateOf(true) }

    DisposableEffect(videoUrl, retryCount) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) buffering = false
            }

            override fun onPlayerError(error: PlaybackException) {
                onError()
            }
        }
        player.addListener(listener)
        val factory = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory())
        player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(videoUrl)))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
            }
            delay(300)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            PlayerSurface(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            )
            if (buffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                PlayPauseButton(
                    player = player,
                    modifier = Modifier
                        .align(Alignment.Center),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(DateUtils.formatElapsedTime(position / 1000), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = position.toFloat(),
                onValueChange = { dragging = true; position = it.toLong() },
                onValueChangeFinished = { player.seekTo(position); dragging = false },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(DateUtils.formatElapsedTime(duration / 1000), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VideoError(onRetry: () -> Unit) {
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.couldn_t_load_this_video), color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.check_your_connection_and_try_again),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}
