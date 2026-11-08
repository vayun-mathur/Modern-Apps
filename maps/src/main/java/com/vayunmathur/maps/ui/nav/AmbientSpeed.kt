package com.vayunmathur.maps.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.util.isImperialUnits

private const val MPH_TO_MPS = 0.44704
private const val KMH_TO_MPS = 1.0 / 3.6

/** Show at or above 10 mph, hide below 8 mph: the 2 mph band stops flicker at the threshold. */
private const val SHOW_MPH = 10.0
private const val HIDE_MPH = 8.0

/** Show at or above 15 km/h, hide below 12 km/h: the metric mirror of the mph band. */
private const val SHOW_KMH = 15.0
private const val HIDE_KMH = 12.0

/**
 * The navigation speedometer, shown while moving even outside navigation.
 *
 * This is [SpeedWidget] itself — not a second speed view — with a visibility
 * latch around it: it appears once the GPS speed reaches 10 mph / 15 km/h and
 * stays until it drops below 8 mph / 12 km/h, so hovering at the threshold
 * does not flicker it. The unit follows the posted limit's authored unit when
 * one is known, otherwise the regional setting — the same decision
 * [SpeedWidget] makes, computed here too so the latch and the readout agree.
 *
 * Below the show threshold (stationary or slow) this composes nothing.
 */
@Composable
fun AmbientSpeed(
    speedMps: Float,
    postedLimit: PostedLimit?,
    darkBasemap: Boolean,
    modifier: Modifier = Modifier,
) {
    val useMph = postedLimit?.displayIsMph ?: isImperialUnits()
    val showAt = (if (useMph) SHOW_MPH * MPH_TO_MPS else SHOW_KMH * KMH_TO_MPS).toFloat()
    val hideBelow = (if (useMph) HIDE_MPH * MPH_TO_MPS else HIDE_KMH * KMH_TO_MPS).toFloat()
    // A latch, not a plain threshold: convergent (idempotent boolean writes),
    // so assigning during composition settles instead of looping.
    var visible by remember { mutableStateOf(false) }
    if (speedMps >= showAt) visible = true
    else if (speedMps < hideBelow) visible = false
    if (!visible) return
    SpeedWidget(
        speedMps = speedMps,
        postedLimit = postedLimit,
        modifier = modifier,
        defaultMph = useMph,
        darkBasemap = darkBasemap,
    )
}
