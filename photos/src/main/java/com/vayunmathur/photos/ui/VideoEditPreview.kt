package com.vayunmathur.photos.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlayCircle
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.platform.VideoEditViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(UnstableApi::class)
@Composable
internal fun VideoEditPreview(
    vm: VideoEditViewModel,
    state: VideoEditState,
    uri: android.net.Uri,
    showCropOverlay: Boolean,
) {
    val context = LocalContext.current
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var isPlaying by remember { androidx.compose.runtime.mutableStateOf(true) }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // Report duration up to the view model once known.
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            val d = exoPlayer.duration
            if (d > 0) {
                vm.setDuration(d)
                break
            }
            delay(150)
        }
    }

    // Live crop/rotate/filter effects.
    LaunchedEffect(
        state.rotationDegrees, state.flipHorizontal,
        state.cropLeft, state.cropTop, state.cropRight, state.cropBottom,
        state.brightness, state.contrast, state.saturation, state.filterPreset,
    ) {
        exoPlayer.setVideoEffects(vm.buildVideoEffects(state))
    }

    // Trim preview: re-clip the source when the committed range changes.
    LaunchedEffect(state.trimStartMs, state.trimEndMs, state.durationMs) {
        if (state.durationMs <= 0L) return@LaunchedEffect
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(state.trimStartMs)
                    .setEndPositionMs(if (state.trimEndMs > 0L) state.trimEndMs else state.durationMs)
                    .build()
            )
            .build()
        val wasPlaying = exoPlayer.playWhenReady
        exoPlayer.setMediaItem(item)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = wasPlaying
    }

    // Mute.
    LaunchedEffect(state.muted) {
        exoPlayer.volume = if (state.muted) 0f else 1f
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio =
                        (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(videoAspectRatio),
            contentAlignment = Alignment.Center,
        ) {
            PlayerSurface(
                player = exoPlayer,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            )
            if (showCropOverlay) {
                CropOverlay(
                    state = state,
                    onCrop = { r ->
                        if (r.left <= 0.001f && r.top <= 0.001f && r.right >= 0.999f && r.bottom >= 0.999f) {
                            vm.clearCrop()
                        } else {
                            vm.setCrop(r.left, r.top, r.right, r.bottom)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
            if (isPlaying) {
                IconPause(modifier = Modifier.size(56.dp), tint = Color.White.copy(alpha = 0.85f))
            } else {
                IconPlayCircle(modifier = Modifier.size(56.dp), tint = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}
