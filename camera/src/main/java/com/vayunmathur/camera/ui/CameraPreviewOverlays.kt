package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.library.ui.Text

@Composable
internal fun BoxScope.CameraPreviewOverlays(
    gridEnabled: Boolean,
    levelEnabled: Boolean,
    isPhotoType: Boolean,
    roll: Float,
    modifier: Modifier,
    timerCountdown: Int,
    longExposureProgress: Float,
    longExposureRemaining: String,
    lowLightDetected: Boolean,
    nightExtAvailable: Boolean,
    nightModeActive: Boolean,
    onToggleNightOverride: () -> Unit,
    iconRotation: Float,
    extensionStrength: Int?,
    photoSessionActive: Boolean,
    analysisStreamActive: Boolean,
) {
    if (gridEnabled) {
        GridOverlay(
            modifier = modifier
        )
    }

    if (levelEnabled && isPhotoType) {
        LevelOverlay(
            roll = roll,
            modifier = modifier
        )
    }

    if (timerCountdown > 0) {
        Text(
            text = "$timerCountdown",
            fontSize = 96.sp,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }

    if (longExposureProgress > 0f) {
        LongExposureOverlay(
            progress = longExposureProgress,
            remainingText = longExposureRemaining
        )
    }

    // Auto night: shown when a dark scene is detected (getNightModeIndicator /
    // luminance) and the extension is usable; tap toggles night off for this scene.
    if (lowLightDetected && nightExtAvailable) {
        NightModeButton(
            active = nightModeActive,
            onClick = onToggleNightOverride,
            iconRotation = iconRotation,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
        )
    }

    // Live vendor night-processing strength while the NIGHT extension preview is bound.
    extensionStrength?.let { strength ->
        Text(
            text = stringResource(R.string.night_2, strength),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .background(Color(0x66000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }

    // Some vendor NIGHT extensions can't host a concurrent ImageAnalysis stream, so
    // the session binds without one and QR codes stop being read. Say so rather than
    // letting the scanner look broken.
    if (isPhotoType && photoSessionActive && !analysisStreamActive) {
        Text(
            text = stringResource(R.string.qr_scanning_paused),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 84.dp)
                .background(Color(0x66000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
