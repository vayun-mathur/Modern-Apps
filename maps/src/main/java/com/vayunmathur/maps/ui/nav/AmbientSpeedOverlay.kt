package com.vayunmathur.maps.ui.nav

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.ui.theme.MapChromeMetrics

/**
 * Browse-mode placement for [AmbientSpeed]: bottom-start, above the scale bar,
 * lifted by [liftPx] so it clears the search bar instead of sitting behind it.
 *
 * The caller owns the browse-only gate (no navigation, no sheet up) via
 * [visible]; while hidden this composes nothing, so the latch inside
 * [AmbientSpeed] restarts and the readout reappears only once the speed again
 * reaches the show threshold.
 */
@Composable
fun BoxScope.AmbientSpeedOverlay(
    visible: Boolean,
    speedMps: Float,
    postedLimit: PostedLimit?,
    darkBasemap: Boolean,
    liftPx: Int,
) {
    if (!visible) return
    AmbientSpeed(
        speedMps = speedMps,
        postedLimit = postedLimit,
        darkBasemap = darkBasemap,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .offset { IntOffset(0, -liftPx) }
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(
                start = MapChromeMetrics.chromeMargin,
                bottom = MapChromeMetrics.aboveScaleBar,
            ),
    )
}
