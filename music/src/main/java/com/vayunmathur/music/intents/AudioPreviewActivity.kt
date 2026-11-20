package com.vayunmathur.music.intents

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.music.platform.formatDuration
import kotlinx.coroutines.delay

/**
 * Lightweight popup player for a single audio file, mirroring AOSP Music's `.AudioPreview`:
 * it handles ACTION_VIEW for an audio uri (file/content/http) and plays just that one track
 * in a small dialog over whatever app launched it — it does NOT open the full Music app.
 *
 * Self-contained (its own ExoPlayer, no library/database dependency) so it stays fast and
 * works before the library has been scanned.
 */
private const val POSITION_POLL_MS = 250L

/** Audio Preview Activity: a component activity implementation for audio preview activity. */
class AudioPreviewActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }

        // Mirror PlaybackService: request audio focus (second arg true) so
        // other apps duck/pause, and pause on audio-becoming-noisy
        // (headphones unplugged) instead of blasting through the speaker.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        player = exo

        val title = resolveTitle(uri)

        setContent {
            DynamicTheme {
                // Full-screen scrim; tapping outside the card dismisses (the activity window
                // is translucent, so the launching app shows through).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    // Swallow taps on the card so they don't dismiss.
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                    ) {
                        PreviewCard(exo, title) { finish() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    /** Best-effort display title: metadata TITLE, else content DISPLAY_NAME, else file name. */
    private fun resolveTitle(uri: Uri): String {
        runCatching {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(this, uri)
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1 && c.moveToFirst()) {
                        c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
    }
}

@Composable
private fun PreviewCard(player: ExoPlayer, title: String, onClose: () -> Unit) {
    var isPlaying by remember { mutableStateOf(player.playWhenReady) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                val d = player.duration
                if (d > 0) duration = d
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Poll position while not actively scrubbing.
    LaunchedEffect(Unit) {
        while (true) {
            if (!scrubbing) {
                position = player.currentPosition.coerceAtLeast(0L).toFloat()
                if (player.duration > 0) duration = player.duration
            }
            delay(POSITION_POLL_MS)
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconLibraryMusic()
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                IconButton(onClick = onClose) { IconClose() }
            }

            val total = duration.coerceAtLeast(0L)
            Slider(
                value = if (total > 0) position.coerceIn(0f, total.toFloat()) else 0f,
                onValueChange = { scrubbing = true; position = it },
                onValueChangeFinished = {
                    player.seekTo(position.toLong())
                    scrubbing = false
                },
                valueRange = 0f..(if (total > 0) total.toFloat() else 1f),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(position.toLong()), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    }) { if (isPlaying) IconPause() else IconPlay() }
                }
                Text(formatDuration(total), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
