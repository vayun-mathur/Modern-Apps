package com.vayunmathur.photos.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlayCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    modifier: Modifier,
    uri: Uri,
    isMetadataVisible: Boolean,
    isSettledPage: Boolean,
) {
    val context = LocalContext.current

    // Default to a sane ratio until the player loads the real one
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val exoPlayer = remember(uri) {
        // Mirror music PlaybackService: request audio focus (second arg true)
        // so other apps duck/pause, and pause on audio-becoming-noisy
        // (headphones unplugged) instead of blasting through the speaker.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = isSettledPage
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }

    var isPlaying by remember { mutableStateOf(isSettledPage) }
    LaunchedEffect(isSettledPage) {
        if (isSettledPage) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    // Seek bar state. durationMs is 0 until the player is ready (duration is C.TIME_UNSET,
    // i.e. negative, before then). While the user drags, positionMs is frozen and scrubPos
    // drives the slider so polling can't fight the thumb.
    var durationMs by remember(uri) { mutableLongStateOf(0L) }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }
    var isScrubbing by remember(uri) { mutableStateOf(false) }
    var scrubPos by remember(uri) { mutableFloatStateOf(0f) }

    LaunchedEffect(exoPlayer, isScrubbing) {
        while (isActive && !isScrubbing) {
            val d = exoPlayer.duration
            durationMs = if (d > 0) d else 0L
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(200)
        }
    }

    DisposableEffect(exoPlayer) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    // Calculate ratio from the actual video stream
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoAspectRatio =
                            (videoSize.width * videoSize.pixelWidthHeightRatio) /
                                videoSize.height
                    }
                }
            }

        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // The container ensures the surface stays centered and "fitted"
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PlayerSurface(
                player = exoPlayer,
                modifier =
                    Modifier.fillMaxWidth() // Try to fill width
                        .aspectRatio(videoAspectRatio),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW
            )
        }

        // Play/Pause Overlay
        FadeVisibility(visible = isMetadataVisible) {
            IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                if (isPlaying) IconPause(
                    modifier = Modifier.size(64.dp),
                    tint = Color.White.copy(alpha = 0.8f)
                ) else IconPlayCircle(
                    modifier = Modifier.size(64.dp),
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        // Seek bar overlay, shares the metadata-visible gate with the play/pause button.
        // Anchored to the top so it never sits under the centered play/pause control or
        // the bottom metadata/button card, both of which are drawn over the video.
        FadeVisibility(
            visible = isMetadataVisible && durationMs > 0,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val sliderValue =
                (if (isScrubbing) scrubPos else positionMs.toFloat())
                    .coerceIn(0f, durationMs.toFloat())
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        isScrubbing = true
                        scrubPos = it
                    },
                    onValueChangeFinished = {
                        exoPlayer.seekTo(scrubPos.toLong())
                        positionMs = scrubPos.toLong()
                        isScrubbing = false
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatVideoTime(sliderValue.toLong()),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        formatVideoTime(durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
