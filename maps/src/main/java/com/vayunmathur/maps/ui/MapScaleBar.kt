package com.vayunmathur.maps.ui
import com.vayunmathur.maps.platform.isImperialUnits

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Web-Mercator scale bar bound to the map camera. Given the current [zoom] and
 * [latitude] (Mercator ground resolution depends on both), it picks the largest
 * "nice" round distance (1 / 2 / 5 × 10ⁿ) that fits within a fixed on-screen
 * width and draws a labelled bar for it.
 *
 * Kept declarative so it just reads `camera.position.zoom` /
 * `camera.position.zoom` / `camera.position.target.latitude` from the caller.
 */
@Composable
fun MapScaleBar(zoom: Double, latitude: Double, modifier: Modifier = Modifier) {
    val metersPerDp = metersPerDp(zoom, latitude)
    if (!metersPerDp.isFinite() || metersPerDp <= 0.0) return

    val imperial = isImperialUnits()
    val (barMeters, label) = scaleBar(metersPerDp * MAX_BAR_DP, imperial)
    val barWidth = (barMeters / metersPerDp).toFloat().dp

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .width(barWidth)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

/** Longest the bar is allowed to get, in dp. */
internal const val MAX_BAR_DP = 96.0

private const val EQUATOR_METERS = 40075016.686

/** Logical pixels per tile. Must match `Mercator.TILE_SIZE` / the Rust renderer's `TILE_SIZE`. */
private const val TILE_SIZE_DP = 512.0

/**
 * Web-Mercator ground resolution in metres per logical pixel (dp).
 *
 * Everything downstream of the camera — `Mercator`, the Rust renderer's `TILE_SIZE`, the `Dp`
 * this bar is laid out in — is a 512-dp tile grid, so this must divide by 512 and must stay in
 * dp. The widely quoted 156543.03392 is the same figure for a 256-px tile, which is a factor of
 * two out here, and pairing any of it with a device pixel length brings in a second factor of
 * the screen density.
 */
internal fun metersPerDp(zoom: Double, latitude: Double): Double =
    EQUATOR_METERS * cos(latitude * PI / 180.0) / (TILE_SIZE_DP * 2.0.pow(zoom))

/** Largest value of the form 1/2/5 × 10ⁿ that is ≤ [maxMeters]. */
private fun niceRoundDistance(maxMeters: Double): Double {
    if (maxMeters <= 0.0) return 1.0
    val magnitude = 10.0.pow(floor(log10(maxMeters)))
    var best = magnitude
    for (factor in listOf(1.0, 2.0, 5.0, 10.0)) {
        val candidate = factor * magnitude
        if (candidate <= maxMeters) best = candidate
    }
    return best
}

/**
 * Pick a "nice" round scale-bar distance that fits within [maxMeters] and label
 * it in the regional unit ([imperial] = ft/mi, else m/km). Returns the distance
 * in METERS (for bar width) paired with the display label.
 */
internal fun scaleBar(maxMeters: Double, imperial: Boolean): Pair<Double, String> {
    if (imperial) {
        val maxMiles = maxMeters / 1609.34
        return if (maxMiles < 1.0) {
            val ft = niceRoundDistance(maxMeters * 3.28084)
            (ft / 3.28084) to "${ft.roundToInt()} ft"
        } else {
            val mi = niceRoundDistance(maxMiles)
            (mi * 1609.34) to "${mi.roundToInt()} mi"
        }
    }
    val meters = niceRoundDistance(maxMeters)
    return meters to if (meters >= 1000.0) {
        "${(meters / 1000.0).roundToInt()} km"
    } else {
        "${meters.roundToInt()} m"
    }
}
