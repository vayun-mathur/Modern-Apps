package com.vayunmathur.maps.ui.nav
import com.vayunmathur.maps.platform.isImperialUnits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.ui.theme.SpeedSign
import kotlin.math.roundToInt

private const val MPS_TO_KMH = 3.6
private const val MPS_TO_MPH = 2.2369363

/**
 * Speedometer + posted-limit badge.
 *
 * Shows the current GPS ground speed in a rounded pill, and — when a posted
 * limit is available from the maxspeed overlay (P5b) — a circular red-ringed
 * limit sign beside it. The display unit follows the posted limit's authored
 * unit when present, otherwise [defaultMph].
 *
 * The current-speed pill tints red when the driver exceeds the posted limit.
 */
@Composable
fun SpeedWidget(
    speedMps: Float,
    postedLimit: PostedLimit?,
    modifier: Modifier = Modifier,
    defaultMph: Boolean = isImperialUnits(),
    darkBasemap: Boolean = false,
) {
    val useMph = postedLimit?.displayIsMph ?: defaultMph
    val speed = if (useMph) (speedMps * MPS_TO_MPH).roundToInt() else (speedMps * MPS_TO_KMH).roundToInt()
    val limitValue = postedLimit?.let { if (useMph) it.mph else it.kmh }
    val over = limitValue != null && speed > limitValue + 2

    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Current speed pill.
        Box(
            Modifier
                .shadow(4.dp, CircleShape)
                .background(
                    if (over) SpeedSign.ring else MaterialTheme.colorScheme.surface,
                    CircleShape,
                )
                .size(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    speed.toString().coerceAtMostLength(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (over) Color.White else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (useMph) "mph" else "km/h",
                    fontSize = 9.sp,
                    color = if (over) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }

        // Posted-limit sign (only when we have a limit). White disc, red ring, near-black
        // numerals in BOTH themes: this is a reproduction of a legal road sign, and there is
        // no dark-mode variant of one. The speed pill beside it does follow the theme.
        //
        // Over a dark basemap the white disc is the brightest thing on screen, so it gains a
        // hairline ring in the ink colour. Drawn INSIDE the fixed 56 dp box (ring, then 1 dp
        // of padding, then the disc) so the sign's colours and its footprint both stay put —
        // an outer ring would paint under the red one, and a larger box would make the widget
        // change size with the theme.
        if (limitValue != null) {
            Box(
                Modifier
                    .shadow(4.dp, CircleShape)
                    .size(56.dp)
                    .then(
                        if (darkBasemap) {
                            Modifier.border(1.dp, SpeedSign.ink, CircleShape).padding(1.dp)
                        } else {
                            Modifier
                        }
                    )
                    .background(SpeedSign.disc, CircleShape)
                    .border(4.dp, SpeedSign.ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    limitValue.toString(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = SpeedSign.ink,
                )
            }
        }
    }
}

/** Guard against absurd speed readings blowing out the pill width. */
private fun String.coerceAtMostLength(): String = if (length > 3) "999" else this
