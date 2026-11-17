package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.youpipe.network.SponsorSegment

/**
 * Slim notification-style replacement for the player seek slider: a thin bar showing buffered and
 * played portions with sponsor-segment markers, tap-to-seek. The played portion has two visual
 * states mirroring the notification media controls: full-strength while playing, dimmed while
 * paused.
 */
@Composable
fun VideoPlayerNotificationProgressBar(
    currentPosition: Long,
    bufferedPosition: Long,
    duration: Long,
    isPlaying: Boolean,
    sponsorSegments: List<SponsorSegment>,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playedFraction = if (duration > 0) currentPosition.toFloat() / duration else 0f
    val bufferedFraction = if (duration > 0) bufferedPosition.toFloat() / duration else 0f
    val playedColor =
        if (isPlaying) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f)
    // 28dp touch target around the 4dp visual bar so taps land without pixel-hunting.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = currentPosition.toFloat(),
                    range = 0f..duration.toFloat().coerceAtLeast(1f),
                )
            }
            .pointerInput(duration, onSeekTo) {
                detectTapGestures(
                    onTap = { offset ->
                        if (duration > 0) {
                            onSeekTo(
                                ((offset.x / size.width) * duration).toLong().coerceIn(0L, duration),
                            )
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            drawRect(color = Color.White.copy(alpha = 0.2f))
            drawRect(
                color = Color.White.copy(alpha = 0.3f),
                size = Size(size.width * bufferedFraction.coerceIn(0f, 1f), size.height),
            )
            sponsorSegments.forEach { segment ->
                val startX = (segment.start.toFloat() / duration) * size.width
                val endX = (segment.end.toFloat() / duration) * size.width
                drawRect(
                    color = Color.Yellow.copy(alpha = 0.7f),
                    topLeft = Offset(startX, 0f),
                    size = Size(endX - startX, size.height),
                )
            }
            drawRect(
                color = playedColor,
                size = Size(size.width * playedFraction.coerceIn(0f, 1f), size.height),
            )
        }
    }
}
