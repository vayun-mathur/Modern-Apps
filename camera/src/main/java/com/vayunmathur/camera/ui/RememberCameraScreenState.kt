package com.vayunmathur.camera.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.vayunmathur.camera.platform.CameraViewModel

/**
 * Collects every [CameraViewModel] flow that [CameraScreenState] reads
 * into the holder in one place. Extracted verbatim from
 * `CameraScreenState.kt`; the holder class itself stays there.
 */
@Composable
internal fun rememberCameraScreenState(viewModel: CameraViewModel): CameraScreenState {
    val cameraMode by viewModel.cameraMode.collectAsState()
    val lensFacing by viewModel.lensFacing.collectAsState()
    val selectedLens by viewModel.selectedLens.collectAsState()
    val availableLenses by viewModel.availableLenses.collectAsState()
    val hasFlashUnit by viewModel.hasFlashUnit.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
    val videoCodec by viewModel.videoCodec.collectAsState()
    val audioCodec by viewModel.audioCodec.collectAsState()
    val audioInputSource by viewModel.audioInputSource.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDurationSec.collectAsState()
    val timerCountdown by viewModel.timerCountdown.collectAsState()
    val qrResult by viewModel.qrResult.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val mirrorFront by viewModel.mirrorFront.collectAsState()
    val timerDuration by viewModel.timerDuration.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val burstActive by viewModel.burstActive.collectAsState()
    val burstCount by viewModel.burstCount.collectAsState()
    val focusLocked by viewModel.focusLocked.collectAsState()
    val recordingPaused by viewModel.recordingPaused.collectAsState()
    val micMuted by viewModel.micMuted.collectAsState()
    val videoSnapshotSupported by viewModel.videoSnapshotSupported.collectAsState()
    val lastCaptureUri by viewModel.lastCaptureUri.collectAsState()
    val gridEnabled by viewModel.gridEnabled.collectAsState()
    val levelEnabled by viewModel.levelEnabled.collectAsState()
    val roll by viewModel.roll.collectAsState()
    val blurStrength by viewModel.blurStrength.collectAsState()
    val exposureComp by viewModel.exposureCompensation.collectAsState()
    val warmth by viewModel.warmth.collectAsState()
    val shadows by viewModel.shadows.collectAsState()
    val exposureTimeIndex by viewModel.exposureTimeIndex.collectAsState()
    val manualIsoIndex by viewModel.manualIsoIndex.collectAsState()
    val isoStops by viewModel.isoStops.collectAsState()
    val longExposureProgress by viewModel.longExposureProgress.collectAsState()
    val longExposureRemaining by viewModel.longExposureRemaining.collectAsState()
    val lowLightDetected by viewModel.lowLightDetected.collectAsState()
    val nightModeActive by viewModel.nightModeActive.collectAsState()
    val extensionStrength by viewModel.extensionStrength.collectAsState()
    val sloMoSupported by viewModel.sloMoSupported.collectAsState()
    val highSpeedActive by viewModel.highSpeedActive.collectAsState()
    val photoSessionActive by viewModel.photoSessionActive.collectAsState()
    val analysisStreamActive by viewModel.analysisStreamActive.collectAsState()
    val nightExtAvailable by viewModel.nightExtensionUsable.collectAsState()
    val camera2FallbackBindKey by viewModel.camera2FallbackBindKey.collectAsState()
    val camera2NightSessionActive by viewModel.camera2NightSessionActive.collectAsState()
    val camera2NightFallbackBindKey by viewModel.camera2NightFallbackBindKey.collectAsState()
    val camera2SloMoFallbackBindKey by viewModel.camera2SloMoFallbackBindKey.collectAsState()
    val camera2Recording by viewModel.camera2Recording.collectAsState()
    val sessionBindFailed by viewModel.sessionBindFailed.collectAsState()
    val sessionBindRetry by viewModel.sessionBindRetry.collectAsState()
    val availableZoomLevels by viewModel.availableZoomLevels.collectAsState()
    val galleryBitmap by viewModel.galleryThumbnail.collectAsState()

    val panoSweeping by viewModel.panoramaEngine.isSweeping.collectAsState()
    val panoStitching by viewModel.panoramaEngine.isStitching.collectAsState()
    val panoDots by viewModel.panoramaEngine.guideDots.collectAsState()
    val panoCurrentAngle by viewModel.panoramaEngine.currentAngle.collectAsState()
    val panoDirection by viewModel.panoramaEngine.sweepDirection.collectAsState()
    val panoPitch by viewModel.panoramaEngine.currentPitch.collectAsState()

    // Kept OUTSIDE the remember(keys) below so they survive holder recreation. Without this
    // the portrait bokeh mask (written from BokehAnalyzer's background thread) is reset to
    // null every time a collected value like roll changes, and the preview never blurs.
    val activeSettingState = remember { mutableStateOf<CameraSetting?>(null) }
    val maskBitmapState = remember { mutableStateOf<Bitmap?>(null) }
    val deviceRotationState = remember { mutableIntStateOf(0) }

    return remember(
        cameraMode, lensFacing, selectedLens, availableLenses, hasFlashUnit, flashMode, torchEnabled, videoCodec, audioCodec, audioInputSource, mirrorFront, isRecording, recordingDuration,
        timerCountdown, qrResult, aspectRatio, zoomRatio, timerDuration,
        isCapturing, burstActive, burstCount, focusLocked, recordingPaused, micMuted,
        videoSnapshotSupported, lastCaptureUri, gridEnabled, levelEnabled, roll,
        blurStrength, exposureComp, warmth, shadows, exposureTimeIndex, manualIsoIndex,
        isoStops, longExposureProgress, longExposureRemaining, lowLightDetected,
        nightModeActive, extensionStrength, sloMoSupported, highSpeedActive,
        photoSessionActive, analysisStreamActive, nightExtAvailable,
        camera2FallbackBindKey, camera2NightSessionActive,
        camera2NightFallbackBindKey, camera2SloMoFallbackBindKey, camera2Recording,
        sessionBindFailed, sessionBindRetry,
        availableZoomLevels, galleryBitmap, panoSweeping, panoStitching, panoDots,
        panoCurrentAngle, panoDirection, panoPitch,
    ) {
        CameraScreenState(
            cameraMode = cameraMode,
            lensFacing = lensFacing,
            selectedLens = selectedLens,
            availableLenses = availableLenses,
            hasFlashUnit = hasFlashUnit,
            flashMode = flashMode,
            torchEnabled = torchEnabled,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            audioInputSource = audioInputSource,
            isRecording = isRecording,
            recordingDuration = recordingDuration,
            timerCountdown = timerCountdown,
            qrResult = qrResult,
            aspectRatio = aspectRatio,
            zoomRatio = zoomRatio,
            mirrorFront = mirrorFront,
            timerDuration = timerDuration,
            isCapturing = isCapturing,
            burstActive = burstActive,
            burstCount = burstCount,
            focusLocked = focusLocked,
            recordingPaused = recordingPaused,
            micMuted = micMuted,
            videoSnapshotSupported = videoSnapshotSupported,
            lastCaptureUri = lastCaptureUri,
            gridEnabled = gridEnabled,
            levelEnabled = levelEnabled,
            roll = roll,
            blurStrength = blurStrength,
            exposureComp = exposureComp,
            warmth = warmth,
            shadows = shadows,
            exposureTimeIndex = exposureTimeIndex,
            manualIsoIndex = manualIsoIndex,
            isoStops = isoStops,
            longExposureProgress = longExposureProgress,
            longExposureRemaining = longExposureRemaining,
            lowLightDetected = lowLightDetected,
            nightModeActive = nightModeActive,
            extensionStrength = extensionStrength,
            sloMoSupported = sloMoSupported,
            highSpeedActive = highSpeedActive,
            photoSessionActive = photoSessionActive,
            analysisStreamActive = analysisStreamActive,
            nightExtAvailable = nightExtAvailable,
            availableZoomLevels = availableZoomLevels,
            galleryBitmap = galleryBitmap,
            panoSweeping = panoSweeping,
            panoStitching = panoStitching,
            panoDots = panoDots,
            panoCurrentAngle = panoCurrentAngle,
            panoDirection = panoDirection,
            panoPitch = panoPitch,
            activeSettingState = activeSettingState,
            maskBitmapState = maskBitmapState,
            deviceRotationState = deviceRotationState,
            sessionBindFailed = sessionBindFailed,
            sessionBindRetry = sessionBindRetry,
            camera2FallbackBindKey = camera2FallbackBindKey,
            camera2NightSessionActive = camera2NightSessionActive,
            camera2NightFallbackBindKey = camera2NightFallbackBindKey,
            camera2SloMoFallbackBindKey = camera2SloMoFallbackBindKey,
            camera2Recording = camera2Recording,
        )
    }
}
