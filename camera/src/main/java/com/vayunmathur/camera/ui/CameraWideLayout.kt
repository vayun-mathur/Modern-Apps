package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Adaptive placement for the viewfinder control rows (shutter, mode selector,
 * bottom bar).
 *
 * Phones stack them vertically under the preview. On wide windows they sit side
 * by side — shutter left, mode + bottom stacked right — so they stop eating the
 * preview's vertical space in landscape desktop windows. Slots only: the caller
 * passes the real rows, which capture the screen's state directly.
 */
@Composable
fun CameraWideLayout(
    shutter: @Composable () -> Unit,
    modes: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    sideBySide: Boolean = true,
) {
    if (sideBySide) {
        Row(modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { shutter() }
            Column(Modifier.weight(1f)) { modes() }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            shutter()
            modes()
        }
    }
}
