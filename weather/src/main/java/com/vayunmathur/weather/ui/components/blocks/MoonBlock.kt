package com.vayunmathur.weather.ui.components.blocks

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBedtime
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.weather.R
import com.vayunmathur.weather.domain.formatClockTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Companion to [SunBlock], built to the same shape: a dashed track across the
 * tile with a marker positioned by how much of the moon's time above the
 * horizon has elapsed, over a translucent panel of rise / set times. The marker
 * is the moon itself, shaded to the current phase.
 *
 * [moonPhase] is the bundle's `moon_phase` fraction — 0 and 1 are new, 0.25 is
 * first quarter, 0.5 is full, 0.75 is last quarter.
 */
@Composable
fun MoonBlock(
    moonPhase: Double?,
    moonriseEpochSec: Long?,
    moonsetEpochSec: Long?,
    use24Hour: Boolean,
    /** Where to put the moon marker. Pinned by previews so the arc renders identically. */
    nowEpochSec: Long = System.currentTimeMillis() / 1000,
    /** `utc_offset_seconds` for the forecast location; null renders in the device zone. */
    utcOffsetSeconds: Int? = null,
) {
    SquareBlock {
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(icon = { m, c -> IconBedtime(m, c) }, title = stringResource(R.string.block_moon))
        }

        val arcColor = MaterialTheme.colorScheme.tertiaryContainer
        val litColor = MaterialTheme.colorScheme.primary
        val unlitColor = MaterialTheme.colorScheme.surfaceVariant

        val progress: Float = if (moonriseEpochSec != null && moonsetEpochSec != null) {
            // Unlike the sun, the moon usually sets before it rises on the same
            // calendar date, so the span runs on into the next day.
            val end = if (moonsetEpochSec > moonriseEpochSec) moonsetEpochSec else moonsetEpochSec + 86_400
            ((nowEpochSec - moonriseEpochSec).toDouble() / (end - moonriseEpochSec))
                .coerceIn(0.0, 1.0).toFloat()
        } else 0f

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Half-circle arc from the bottom edge.
            val cx = w / 2f
            val cy = h * 0.7f
            val r = w * 0.35f
            drawArc(
                color = arcColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(
                    width = 3.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                ),
            )
            // Moon marker.
            val theta = Math.toRadians(180 + 180.0 * progress)
            val center = Offset((cx + r * cos(theta)).toFloat(), (cy + r * sin(theta)).toFloat())
            drawMoonDisc(center, 9.dp.toPx(), moonPhase, unlitColor, litColor)
        }

        // Bottom panel with moonrise / moonset times + the phase name.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.46f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
        ) {
            Box(Modifier.fillMaxSize()) {
                HorizontalDivider(Modifier.align(Alignment.TopCenter))
                Column(
                    Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    RiseSetTimeRow(
                        text = moonriseEpochSec?.let { formatClockTime(it, use24Hour, utcOffsetSeconds) }
                            ?: stringResource(R.string.weather_no_data),
                        icon = { m, c -> IconKeyboardArrowUp(m, c) },
                    )
                    RiseSetTimeRow(
                        text = moonsetEpochSec?.let { formatClockTime(it, use24Hour, utcOffsetSeconds) }
                            ?: stringResource(R.string.weather_no_data),
                        icon = { m, c -> IconKeyboardArrowDown(m, c) },
                    )
                    if (moonPhase != null) {
                        RiseSetTimeRow(
                            text = stringResource(moonPhaseNameRes(moonPhase)),
                            icon = { m, c -> IconBedtime(m, c) },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Draws the moon at [center], unlit disc first and then the lit limb: a
 * half-disc closed by a half-ellipse whose width is |cos(2*pi*phase)| * radius.
 * The ellipse bulges away from the lit side when gibbous and into it when
 * crescent, which is the sweep flip below. At the quarters the ellipse is
 * degenerate, the arc is dropped, and close() draws the straight terminator.
 * A null [phase] leaves the disc blank rather than guessing.
 */
private fun DrawScope.drawMoonDisc(
    center: Offset,
    radius: Float,
    phase: Double?,
    unlit: Color,
    lit: Color,
) {
    drawCircle(color = unlit, radius = radius, center = center)
    if (phase == null) return

    val c = cos(2 * PI * phase).toFloat()
    val terminatorRx = abs(c) * radius
    val litPath = Path().apply {
        moveTo(center.x, center.y - radius)
        arcTo(
            Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
            -90f, 180f, false,
        )
        arcTo(
            Rect(center.x - terminatorRx, center.y - radius, center.x + terminatorRx, center.y + radius),
            90f, if (c < 0) 180f else -180f, false,
        )
        close()
    }

    if (phase < 0.5) {
        drawPath(litPath, lit)
    } else {
        scale(scaleX = -1f, scaleY = 1f, pivot = center) { drawPath(litPath, lit) }
    }
}

/** Buckets the moon phase fraction into the eight conventional phase names. */
@StringRes
private fun moonPhaseNameRes(phase: Double): Int = when {
    phase < 0.03 || phase > 0.97 -> R.string.moon_phase_new
    phase < 0.22 -> R.string.moon_phase_waxing_crescent
    phase < 0.28 -> R.string.moon_phase_first_quarter
    phase < 0.47 -> R.string.moon_phase_waxing_gibbous
    phase < 0.53 -> R.string.moon_phase_full
    phase < 0.72 -> R.string.moon_phase_waning_gibbous
    phase < 0.78 -> R.string.moon_phase_last_quarter
    else -> R.string.moon_phase_waning_crescent
}
