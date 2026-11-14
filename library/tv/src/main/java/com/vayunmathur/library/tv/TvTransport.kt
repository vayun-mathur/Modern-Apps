package com.vayunmathur.library.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Half the seek-bar chapter marker width, in pixels. */
private const val MARKER_HALF_WIDTH_PX = 1.5f

/** Width of a seek-bar chapter marker, in pixels. */
private const val MARKER_WIDTH_PX = 3f

/**
 * Player seek bar. Draw-only - the caller owns LEFT/RIGHT scrubbing - but it thickens and
 * shows a thumb while [active] (focused or scrubbing), the visual cue that the arrows seek.
 *
 * @param markers chapter/segment boundaries as 0..1 fractions, drawn as gaps in the track.
 */
@Composable
fun TvSeekBar(
    progress: Float,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
    active: Boolean = false,
    markers: List<Float> = emptyList(),
) {
    val track by animateDpAsState(if (active) 8.dp else 4.dp, label = "tv-seek-track")
    val primary = MaterialTheme.colorScheme.primary
    val thumbColor = MaterialTheme.colorScheme.inverseSurface
    val gapColor = Color.Black.copy(alpha = 0.6f)
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        val h = track.toPx()
        val top = (size.height - h) / 2
        val radius = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, h), radius)
        drawRoundRect(
            Color.White.copy(alpha = 0.38f),
            Offset(0f, top),
            Size(size.width * buffered.coerceIn(0f, 1f), h),
            radius,
        )
        val x = size.width * progress.coerceIn(0f, 1f)
        drawRoundRect(primary, Offset(0f, top), Size(x, h), radius)
        markers.forEach { m ->
            val mx = size.width * m.coerceIn(0f, 1f)
            drawRect(gapColor, Offset(mx - MARKER_HALF_WIDTH_PX, top), Size(MARKER_WIDTH_PX, h))
        }
        if (active) drawCircle(thumbColor, radius = h * 1.1f, center = Offset(x, size.height / 2))
    }
}

/** Elapsed / total labels either side of a [TvSeekBar]. */
@Composable
fun TvSeekRow(
    elapsed: String,
    total: String,
    progress: Float,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
    active: Boolean = false,
    markers: List<Float> = emptyList(),
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(elapsed, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        TvSeekBar(
            progress = progress,
            buffered = buffered,
            active = active,
            markers = markers,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        Text(total, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
