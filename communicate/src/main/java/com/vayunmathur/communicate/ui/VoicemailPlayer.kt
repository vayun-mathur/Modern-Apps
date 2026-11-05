package com.vayunmathur.communicate.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vayunmathur.communicate.R
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Inline audio player for one voicemail row: play/pause plus a scrub slider
 * with elapsed/total labels.
 *
 * A plain [ExoPlayer] (not the session/controller stack music uses) — voicemail
 * clips are short files, not a browsable library. The player is created
 * per expanded row and released when the row leaves composition. [onPlayed]
 * fires once playback starts, so the caller can mark the row read.
 * [requestHeaders] carries auth (GV media needs the session Cookie); empty
 * for carrier rows, whose provider URIs need none.
 */
@OptIn(UnstableApi::class)
@Composable
fun VoicemailPlayer(
    audioUri: String,
    onPlayed: () -> Unit,
    modifier: Modifier = Modifier,
    requestHeaders: Map<String, String> = emptyMap(),
) {
    val context = LocalContext.current
    val playFailed = stringResource(R.string.voicemail_play_failed)
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var scrubTo by remember { mutableFloatStateOf(-1f) }
    var notified by remember { mutableStateOf(false) }

    val player = remember(audioUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(audioUri)))
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        durationMs = duration.coerceAtLeast(0L)
                    }
                    if (state == Player.STATE_ENDED) {
                        playing = false
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    failed = true
                }
            })
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(failed) {
        if (failed) AppMessages.show(playFailed)
    }
    LaunchedEffect(playing) {
        while (isActive && playing) {
            positionMs = player.currentPosition
            delay(250)
        }
        positionMs = player.currentPosition
    }
    LaunchedEffect(playing, notified, onPlayed) {
        if (playing && !notified) {
            notified = true
            onPlayed()
        }
    }

    val shownPosition = (scrubTo.takeIf { it >= 0f }?.toLong() ?: positionMs).coerceIn(0L, durationMs)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = {
                    if (failed) return@FilledIconButton
                    if (player.isPlaying) player.pause() else player.play()
                },
                enabled = !failed,
                modifier = Modifier.size(44.dp),
            ) {
                if (playing) IconPause() else IconPlay()
            }
            Text(
                formatClipTime(shownPosition),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = if (durationMs > 0) shownPosition.toFloat() / durationMs else 0f,
                onValueChange = { scrubTo = it * durationMs },
                onValueChangeFinished = {
                    val target = scrubTo
                    scrubTo = -1f
                    if (target >= 0f) {
                        player.seekTo(target.toLong().coerceIn(0L, durationMs))
                        positionMs = player.currentPosition
                    }
                },
                enabled = !failed && durationMs > 0,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatClipTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** mm:ss for the player labels; the row itself uses the `voicemail_duration` string. */
private fun formatClipTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
