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

/**
 * Whether the moon affordance is shown. Visible whenever a dark scene is detected or
 * night is already active, regardless of vendor NIGHT-extension availability: the
 * custom burst path ([com.vayunmathur.camera.platform.CameraViewModel.captureNightPhotoCustom])
 * works without an extension, and the tap toggles the per-scene override for both paths.
 * Extension-only UI (strength slider) stays gated on the extension separately.
 */
internal fun nightAffordanceVisible(lowLightDetected: Boolean, nightModeActive: Boolean): Boolean =
    lowLightDetected || nightModeActive

@Composable
internal fun BoxScope.CameraPreviewOverlays(
    gridEnabled: Boolean,
    levelEnabled: Boolean,
    isPhotoType: Boolean,
    isQr: Boolean = false,
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

    if (longExposureProgress > 0f || longExposureRemaining.isNotEmpty()) {
        LongExposureOverlay(
            progress = longExposureProgress,
            remainingText = longExposureRemaining
        )
    }

    // Auto night: shown whenever a dark scene is detected / night is active, even without
    // a vendor extension (the custom burst path still applies); tap toggles night off/on
    // for this scene on both the extension and custom paths.
    if (nightAffordanceVisible(lowLightDetected, nightModeActive)) {
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
    if ((isPhotoType || isQr) && photoSessionActive && !analysisStreamActive) {
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
