package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.vayunmathur.library.ui.animatedFloat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** How long the viewfinder stays dimmed after the sensor starts exposing a still. */
private const val FLASH_HOLD_MS = 60L

/** Peak opacity of the black capture dim over the viewfinder. */
private const val FLASH_ALPHA = 0.6f

/**
 * Briefly dims the viewfinder each time [captureEvents] fires (CameraX onCaptureStarted), the
 * stock-camera cue that the picture was taken at that moment. The shutter no longer stays
 * pressed until the Motion Photo clip is saved (#914), so this is the "got it" signal.
 */
@Composable
internal fun CaptureFlashOverlay(captureEvents: Flow<Unit>, modifier: Modifier = Modifier) {
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(captureEvents) {
        captureEvents.collectLatest {
            dimmed = true
            delay(FLASH_HOLD_MS)
            dimmed = false
        }
    }
    val alpha = animatedFloat(if (dimmed) FLASH_ALPHA else 0f)
    if (alpha > 0f) {
        Box(modifier.graphicsLayer { this.alpha = alpha }.background(Color.Black))
    }
}
