package com.vayunmathur.youpipe.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconFullscreen
import com.vayunmathur.library.ui.IconFullscreenExit
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SliderDefaults
import com.vayunmathur.library.ui.Text
import com.vayunmathur.youpipe.network.SponsorSegment

/**
 * The seek row pinned to the bottom of the controls overlay: elapsed/total times,
 * the buffered and position sliders with sponsor-segment markers, and the
 * lock/fullscreen buttons.
 *
 * When [useNotificationStyle] is set (Settings > Player), the sliders are swapped for
 * [VideoPlayerNotificationProgressBar]: a slim notification-style two-state
 * (playing/paused) bar with tap-to-seek.
 */
@Composable
internal fun VideoPlayerSeekRow(
    currentPosition: Long,
    bufferedPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    useNotificationStyle: Boolean,
    sponsorSegments: List<SponsorSegment>,
    onSeekChange: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onSeekTo: (Long) -> Unit,
    isFullscreen: Boolean,
    onLock: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = DateUtils.formatElapsedTime(currentPosition / 1000),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = DateUtils.formatElapsedTime(duration / 1000),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Box(contentAlignment = Alignment.CenterStart) {
                if (useNotificationStyle) {
                    VideoPlayerNotificationProgressBar(
                        currentPosition = currentPosition,
                        bufferedPosition = bufferedPosition,
                        duration = duration,
                        isPlaying = isPlaying,
                        sponsorSegments = sponsorSegments,
                        onSeekTo = onSeekTo,
                    )
                } else {
                    Slider(
                        value = if (duration > 0) bufferedPosition.toFloat() else 0f,
                        onValueChange = { },
                        valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Transparent,
                            activeTrackColor = Color.White.copy(alpha = 0.3f),
                            inactiveTrackColor = Color.Transparent
                        )
                    )
                    if (duration > 0) {
                        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 20.dp)) {
                            sponsorSegments.forEach { segment ->
                                val startX = (segment.start.toFloat() / duration) * size.width
                                val endX = (segment.end.toFloat() / duration) * size.width
                                drawRect(
                                    color = Color.Yellow.copy(alpha = 0.7f),
                                    topLeft = Offset(startX, 0f),
                                    size = Size(endX - startX, size.height)
                                )
                            }
                        }
                    }
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() else 0f,
                        onValueChange = { onSeekChange(it.toLong()) },
                        onValueChangeFinished = onSeekFinished,
                        valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }
        Spacer(Modifier.padding(4.dp))
        if (isFullscreen) {
            IconButton(onClick = onLock) {
                IconLock(tint = Color.White)
            }
        }
        IconButton({ onFullscreenChange(!isFullscreen) }) {
            if (isFullscreen) IconFullscreenExit() else IconFullscreen()
        }
    }
}
