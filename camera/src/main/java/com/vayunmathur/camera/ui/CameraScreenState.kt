package com.vayunmathur.camera.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.setValue
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.AudioCodec
import com.vayunmathur.camera.platform.AudioInputSource
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.camera.platform.VideoCodec

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
    /** Bind key latched after a Camera2 failure (bind error shows), or null. */
    val camera2FallbackBindKey: String? = null,
    /** Phase 6: a Camera2 NIGHT extension session is live (strength hides). */
    val camera2NightSessionActive: Boolean = false,
    /** Bind key latched after a Camera2 extension preview failure, or null. */
    val camera2NightFallbackBindKey: String? = null,
    /** Bind key latched after a Camera2 slo-mo failure, or null. */
    val camera2SloMoFallbackBindKey: String? = null,
    /** True while a Camera2 recording engine owns the camera. */
    val camera2Recording: Boolean = false,
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
                cameraMode,
                fallbackLatched = camera2FallbackBindKey == bindKey,
            )
        }

    /**
     * True when the Camera2 NIGHT extension preview owns the viewfinder:
     * PHOTO, night engaged (luma hysteresis / indicator via the existing
     * flows), the persisted Camera2 night gate on, and no extension-preview
     * failure latched for this bind. The extension session itself re-probes
     * the lens at open (`Camera2ExtensionProbe`); a probe/configure failure
     * latches `camera2NightFallbackBindKey` and the regular preview
     * (Camera2 or CameraX, with CameraX-extension → own-burst capture
     * order) serves instead. This intentionally does NOT require the
     * CameraX extension to be usable — policy order is Camera2 first.
     */
    val useCamera2NightPreview: Boolean
        get() {
            if (cameraMode != CameraMode.PHOTO || !nightModeActive) return false
            val cameraId = selectedLens?.logicalCameraId
            if (cameraId.isNullOrEmpty()) return false
            val bindKey = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch
                .bindKey(cameraId, cameraMode)
            return camera2NightFallbackBindKey != bindKey
        }

    /**
     * True when the Camera2 slo-mo preview owns the viewfinder. Requires
     * SLOW_MO, the slo-mo gate, no latched fallback, and
     * `SloMoRecordingReadiness.recordingEngineReady` — false until device
     * verification (piece 6), so this is false today and the CameraX
     * high-speed preview serves. The preview surface plumbing itself
     * (piece 4) is ready behind this gate.
     */
    val useCamera2SloMoPreview: Boolean
        get() {
            if (cameraMode != CameraMode.SLOW_MO) return false
            val cameraId = selectedLens?.logicalCameraId
            if (cameraId.isNullOrEmpty()) return false
            val bindKey = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch
                .bindKey(cameraId, cameraMode)
            return com.vayunmathur.camera.platform.camera2.SloMoPreviewPolicy.livePreviewSelected(
                isSlowMoMode = true,
                fallbackLatched = camera2SloMoFallbackBindKey == bindKey,
                hasSelection = true,
                recordingEngineReady =
                    com.vayunmathur.camera.platform.camera2.SloMoRecordingReadiness.recordingEngineReady,
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
            val engine = com.vayunmathur.camera.platform.camera2.NightEngine.CAMERA2_EXTENSION
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

    val mirrorPreview get() = lensFacing == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT && !mirrorFront

    /** Front lens with no LED unit: stills flash via the screen instead. */
    val screenFlashAvailable get() = com.vayunmathur.camera.domain.FlashModePolicy.usesScreenFlash(
        if (lensFacing == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT) {
            com.vayunmathur.camera.domain.LensFacing.FRONT
        } else {
            com.vayunmathur.camera.domain.LensFacing.BACK
        },
        hasFlashUnit,
    )
}
