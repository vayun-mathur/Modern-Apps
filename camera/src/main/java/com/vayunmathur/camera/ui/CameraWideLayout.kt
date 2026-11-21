package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Placement for the viewfinder control rows (shutter, mode selector, bottom bar).
 *
 * Slots only: the caller passes the real rows and decides [sideBySide]. The
 * camera caller always stacks them vertically (see
 * [captureControlsSideBySide]) so rotation never rearranges the controls.
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
