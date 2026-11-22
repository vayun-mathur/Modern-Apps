package com.vayunmathur.camera.ui

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.AudioCodec
import com.vayunmathur.camera.platform.AudioInputSource
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.camera.platform.VideoCodec
import com.vayunmathur.camera.platform.BokehAnalyzer

/**
 * All UI state read by [CameraScreen], collected from [CameraViewModel] flows in one
 * place so the screen itself stays a thin layout. Also owns the two pieces of
 * composition-local state (open settings pane, portrait mask bitmap, orientation).
 *
 * Collection happens in [rememberCameraScreenState] (a @Composable context); this class is a
 * plain snapshot holder so the screen stays a thin layout.
 */
    internal class CameraScreenState(
    val cameraMode: CameraMode,
    val lensFacing: Int,
    val selectedLens: com.vayunmathur.camera.domain.PhysicalLens?,
    val availableLenses: List<com.vayunmathur.camera.domain.PhysicalLens>,
    val hasFlashUnit: Boolean,
    val flashMode: FlashMode,
    val torchEnabled: Boolean,
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec = AudioCodec.AAC,
    val audioInputSource: AudioInputSource,
    val isRecording: Boolean,
    val recordingDuration: Long,
    val timerCountdown: Int,
    val qrResult: String?,
    val aspectRatio: AspectRatioOption,
    val zoomRatio: Float,
    val mirrorFront: Boolean,
    val timerDuration: TimerDuration,
    val isCapturing: Boolean,
    val burstActive: Boolean,
    val burstCount: Int,
    val focusLocked: Boolean,
    val recordingPaused: Boolean,
    val micMuted: Boolean,
    val videoSnapshotSupported: Boolean,
    val lastCaptureUri: android.net.Uri?,
    val gridEnabled: Boolean,
    val levelEnabled: Boolean,
    val roll: Float,
    val blurStrength: Float,
    val exposureComp: Float,
    val warmth: Float,
    val shadows: Float,
    val exposureTimeIndex: Int,
    val manualIsoIndex: Int,
    val isoStops: List<Int>,
    val longExposureProgress: Float,
    val longExposureRemaining: String,
    val lowLightDetected: Boolean,
    val nightModeActive: Boolean,
    val extensionStrength: Int?,
    val sloMoSupported: Boolean?,
    val highSpeedActive: Boolean,
    val photoSessionActive: Boolean,
    val analysisStreamActive: Boolean,
    val nightExtAvailable: Boolean,
    val surfaceRequest: androidx.camera.core.SurfaceRequest?,
    val availableZoomLevels: List<Pair<String, Float>>,
    val galleryBitmap: Bitmap?,
    val panoSweeping: Boolean,
    val panoStitching: Boolean,
    val panoDots: List<com.vayunmathur.camera.platform.GuideDot>,
    val panoCurrentAngle: Float,
    val panoDirection: Int,
    val panoPitch: Float,
    // Composition-local state that must OUTLIVE this holder: rememberCameraScreenState
    // recreates the holder whenever any collected value changes (roll, zoom, ...), which
    // is many times a second. These are backed by stable remember{}s so the portrait mask
    // survives long enough for the preview to render it, and the open settings pane and
    // icon rotation don't reset. Passing the backing state in (rather than declaring
    // mutableStateOf here) means every recreated holder shares the same instance.
    activeSettingState: MutableState<CameraSetting?>,
    maskBitmapState: MutableState<Bitmap?>,
    deviceRotationState: MutableIntState,
    val sessionBindFailed: Boolean = false,
    val sessionBindRetry: Int = 0,
    /** Camera2 preview gate (default ON); policy-gated to PHOTO in the getter below. */
    val camera2PreviewEnabled: Boolean = true,
    /** Bind key latched to CameraX after a Camera2 failure, or null. */
    val camera2FallbackBindKey: String? = null,
    /** Phase 6: a Camera2 NIGHT extension session is live (strength hides). */
    val camera2NightSessionActive: Boolean = false,
) {
    var activeSetting by activeSettingState
    var maskBitmap by maskBitmapState
    var deviceRotation by deviceRotationState

    val isPhotoType get() = cameraMode in listOf(CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.PANORAMA, CameraMode.PHOTOSPHERE)
    val isSloMo get() = cameraMode == CameraMode.SLOW_MO
    val isVideoType get() = cameraMode == CameraMode.VIDEO || cameraMode == CameraMode.TIMELAPSE || cameraMode == CameraMode.CINEMATIC
    val isPanoType get() = cameraMode == CameraMode.PANORAMA || cameraMode == CameraMode.PHOTOSPHERE
    val isPortrait get() = cameraMode == CameraMode.PORTRAIT
    val sessionKind get() = when {
        isSloMo -> SessionKind.HIGH_SPEED
        isVideoType -> SessionKind.VIDEO
        isPanoType -> SessionKind.PANORAMA
        isPortrait -> SessionKind.PORTRAIT
        else -> SessionKind.PHOTO
    }

    // Auto night: when getNightModeIndicator()/luminance reports a dark scene (nightModeActive) in
    // PHOTO mode and the extension is usable, bind the vendor NIGHT extension preview.
    val useNightPreview get() = sessionKind == SessionKind.PHOTO && cameraMode == CameraMode.PHOTO &&
        nightModeActive && nightExtAvailable

    /**
     * True when the Camera2 preview engine owns the viewfinder (gate on +
     * PHOTO, no fallback latch for this bind, lens known). CameraX is not
     * bound in this state; plain PHOTO stills are served by the Camera2
     * session (see StillEnginePolicy for its exclusions).
     */
    val useCamera2Preview: Boolean
        get() {
            val cameraId = selectedLens?.logicalCameraId
            if (cameraId.isNullOrEmpty()) return false
            val bindKey = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch
                .bindKey(cameraId, cameraMode)
            return com.vayunmathur.camera.platform.camera2.PreviewEnginePolicy.usesCamera2(
                camera2PreviewEnabled, cameraMode,
                fallbackLatched = camera2FallbackBindKey == bindKey,
            )
        }

    /**
     * Strength indicator value to show, or null to hide it. There is no
     * Camera2 strength flow, so on the Camera2 night path the indicator is
     * hidden rather than faked; only the CameraX extension shows it.
     */
    val visibleExtensionStrength: Int?
        get() {
            // A strength value only ever exists on a CameraX extension bind;
            // the policy hides it whenever the serving engine is Camera2.
            val engine = if (camera2NightSessionActive) {
                com.vayunmathur.camera.platform.camera2.NightEngine.CAMERA2_EXTENSION
            } else {
                com.vayunmathur.camera.platform.camera2.NightEngine.CAMERAX_EXTENSION
            }
            return if (
                com.vayunmathur.camera.platform.camera2.ExtensionsEnginePolicy
                    .showsExtensionStrength(engine, extensionStrength)
            ) {
                extensionStrength
            } else {
                null
            }
        }

    val previewAspectRatio get() = when {
        // The still crop only exists on the photo sessions (PHOTO/PORTRAIT ImageCapture).
        // Video/HFR/pano never crop to it, so letterboxing their preview to it lies about
        // the framing — they show their native 4:3 frame instead.
        cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT -> when (aspectRatio) {
            AspectRatioOption.RATIO_16_9 -> 9f / 16f
            AspectRatioOption.RATIO_4_3 -> 3f / 4f
            AspectRatioOption.RATIO_3_2 -> 2f / 3f
            AspectRatioOption.RATIO_1_1 -> 1f
        }
        else -> 3f / 4f
    }

    val mirrorPreview get() = lensFacing == CameraSelector.LENS_FACING_FRONT && !mirrorFront

    /** Front lens with no LED unit: stills flash via the screen instead. */
    val screenFlashAvailable get() = com.vayunmathur.camera.domain.FlashModePolicy.usesScreenFlash(
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            com.vayunmathur.camera.domain.LensFacing.FRONT
        } else {
            com.vayunmathur.camera.domain.LensFacing.BACK
        },
        hasFlashUnit,
    )
}

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
    val surfaceRequest by viewModel.surfaceRequest.collectAsState()
    val camera2PreviewEnabled by viewModel.camera2PreviewEnabled.collectAsState()
    val camera2FallbackBindKey by viewModel.camera2FallbackBindKey.collectAsState()
    val camera2NightSessionActive by viewModel.camera2NightSessionActive.collectAsState()
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
        photoSessionActive, analysisStreamActive, nightExtAvailable, surfaceRequest,
        camera2PreviewEnabled, camera2FallbackBindKey, camera2NightSessionActive,
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
            surfaceRequest = surfaceRequest,
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
            camera2PreviewEnabled = camera2PreviewEnabled,
            camera2FallbackBindKey = camera2FallbackBindKey,
            camera2NightSessionActive = camera2NightSessionActive,
        )
    }
}
