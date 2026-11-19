package com.vayunmathur.measure.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CompassCalibrationBanner
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.measure.R
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.platform.CompassActions
import com.vayunmathur.measure.platform.CompassUiState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CompassContent(
    state: CompassUiState,
    actions: CompassActions,
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    val heading = if (state.useTrueNorth && state.hasLocation) {
        state.azimuthTrueDeg
    } else {
        state.azimuthMagDeg
    }
    val cardinals = stringArrayResource(R.array.compass_cardinals)
    AppScaffold(
        title = stringResource(R.string.tool_compass),
        actions = { IconButton(onClick = onOpenSettings) { IconSettings() } },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${Units.formatBearing(heading)} ${cardinals[Units.cardinalIndex(heading)]}",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                // Without a location fix declination is unknown, so "true north" would
                // be a claim the app cannot back up.
                stringResource(
                    if (state.useTrueNorth && state.hasLocation) R.string.compass_true_north
                    else R.string.compass_magnetic_north
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CompassDial(
                headingDeg = heading,
                heldBearingDeg = state.heldBearingDeg,
                cardinals = cardinals,
                // Height-driven so the dial shrinks to fit a landscape window rather
                // than pushing the controls off the bottom.
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .padding(8.dp),
            )

            CompassCalibrationBanner(state.accuracy)
            if (state.tiltWarning) {
                Text(
                    stringResource(R.string.compass_tilt_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.hasLocation) {
                Text(
                    stringResource(
                        R.string.compass_declination,
                        Units.formatAngle(state.declinationDeg),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.compass_no_location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OutlinedButton(
                    onClick = { actions.setUseTrueNorth(!state.useTrueNorth) },
                    enabled = state.hasLocation,
                ) {
                    Text(
                        stringResource(
                            if (state.useTrueNorth) R.string.compass_show_magnetic
                            else R.string.compass_show_true_north
                        )
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (state.heldBearingDeg == null) actions.holdBearing()
                        else actions.clearHeldBearing()
                    }
                ) {
                    Text(
                        stringResource(
                            if (state.heldBearingDeg == null) R.string.compass_hold_bearing
                            else R.string.compass_release_bearing
                        )
                    )
                }
            }
        }
    }
}

/**
 * Compass rose. The dial rotates opposite the heading so north stays physically north
 * while the fixed top index reads the current bearing.
 */
@Composable
private fun CompassDial(
    headingDeg: Double,
    heldBearingDeg: Double?,
    cardinals: Array<String>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawCircle(variant.copy(alpha = 0.3f), radius, center, style = Stroke(2f))

            rotate(-headingDeg.toFloat(), center) {
                drawTicks(center, radius, onSurface, variant)
                drawCardinals(measurer, cardinals, center, radius, onSurface, error)
                if (heldBearingDeg != null) {
                    drawHeldBearing(center, radius, heldBearingDeg.toFloat(), primary)
                }
            }

            // Fixed index at the top: the bearing is whatever sits under it.
            val p = Path().apply {
                moveTo(center.x, center.y - radius - 2f)
                lineTo(center.x - 14f, center.y - radius + 22f)
                lineTo(center.x + 14f, center.y - radius + 22f)
                close()
            }
            drawPath(p, primary)
        }
    }
}

private fun DrawScope.drawTicks(center: Offset, radius: Float, major: Color, minor: Color) {
    for (deg in 0 until 360 step 5) {
        val isMajor = deg % 30 == 0
        val len = if (isMajor) radius * 0.10f else radius * 0.05f
        val rad = Math.toRadians(deg.toDouble() - 90.0)
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        drawLine(
            color = if (isMajor) major else minor.copy(alpha = 0.5f),
            start = Offset(center.x + cosA * (radius - len), center.y + sinA * (radius - len)),
            end = Offset(center.x + cosA * radius, center.y + sinA * radius),
            strokeWidth = if (isMajor) 3f else 1.5f,
        )
    }
}

private fun DrawScope.drawCardinals(
    measurer: TextMeasurer,
    cardinals: Array<String>,
    center: Offset,
    radius: Float,
    normal: Color,
    northColor: Color,
) {
    // Only the four quarter points fit on the dial; the intercardinals in the same array
    // are for the numeric readout.
    for (quarter in 0 until 4) {
        val index = quarter * (Units.CARDINAL_POINTS / 4)
        val label = cardinals[index]
        val rad = Math.toRadians(quarter * 90.0 - 90.0)
        val r = radius * 0.78f
        val style = TextStyle(
            fontSize = 20.sp,
            color = if (index == 0) northColor else normal,
        )
        val layout = measurer.measure(label, style)
        drawText(
            layout,
            topLeft = Offset(
                center.x + cos(rad).toFloat() * r - layout.size.width / 2f,
                center.y + sin(rad).toFloat() * r - layout.size.height / 2f,
            ),
        )
    }
}

private fun DrawScope.drawHeldBearing(
    center: Offset,
    radius: Float,
    bearingDeg: Float,
    color: Color,
) {
    val rad = Math.toRadians(bearingDeg.toDouble() - 90.0)
    drawLine(
        color = color,
        start = center,
        end = Offset(
            center.x + cos(rad).toFloat() * radius,
            center.y + sin(rad).toFloat() * radius,
        ),
        strokeWidth = 4f,
    )
}
