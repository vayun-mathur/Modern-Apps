package com.vayunmathur.camera.platform

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

import com.vayunmathur.library.log.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay

/**
 * Binds [useCases] via a [SessionConfig] with auto-rotation enabled, so CameraX applies the
 * correct output rotation from the device sensors — no manual targetRotation plumbing needed.
 * Every session (photo/night/portrait/pano/video) binds through here.
 */
internal fun CameraViewModel.bindSession(
    provider: ProcessCameraProvider,
    owner: LifecycleOwner,
    selector: CameraSelector,
    vararg useCases: UseCase,
): Camera = provider.bindToLifecycle(
    owner,
    selector,
    SessionConfig.Builder(*useCases).setAutoRotationEnabled(true).build(),
)

/** Bounded bind retries after the first attempt (3 attempts in total). */
internal const val SESSION_BIND_MAX_RETRIES = 2

/** Pause between bind retries: long enough for a transient HAL teardown to settle. */
internal const val SESSION_BIND_RETRY_DELAY_MS = 300L

/**
 * Runs [bind] until it succeeds or [maxRetries] retries are exhausted, waiting
 * [retryDelayMs] (via [delayFn]) between attempts. Cancellation is never
 * swallowed: a [kotlinx.coroutines.CancellationException] from [bind] or
 * [delayFn] propagates, so a session-effect key change (mode/lens/lifecycle)
 * supersedes an in-progress retry ladder immediately.
 */
internal suspend fun runSessionBindWithRetry(
    maxRetries: Int = SESSION_BIND_MAX_RETRIES,
    retryDelayMs: Long = SESSION_BIND_RETRY_DELAY_MS,
    delayFn: suspend (Long) -> Unit = { delay(it) },
    bind: suspend () -> Boolean,
): Boolean {
    var retriesLeft = maxRetries
    while (true) {
        if (bind()) return true
        if (retriesLeft <= 0) return false
        retriesLeft--
        delayFn(retryDelayMs)
    }
}

/**
 * Manual retry after a bind exhausted its retries: clears the failure so the
 * preview placeholder returns, then bumps the retry counter the session
 * effect is keyed on, which cancels the parked effect and rebinds.
 */
fun CameraViewModel.retrySessionBind() {
    sessionBindFailedMutable.value = false
    sessionBindRetryMutable.value += 1
}

/** Tears down whatever session is currently bound and clears the shared preview surface. */
fun CameraViewModel.teardownSession() {
    sessionTeardownCount++
    Log.dev(
        "NightPreview",
        "teardownSession() START thread=${Thread.currentThread().name} " +
            "surface=${surfaceRequestMutable.value?.resolution} " +
            "photoActive=${photoSessionActiveMutable.value} " +
            "nightPreviewActive=${nightPreviewActiveMutable.value} " +
            "highSpeedActive=${highSpeedActiveMutable.value} " +
            "videoActive=${videoSessionActiveMutable.value} boundCamera=${boundCamera != null} " +
            "provider=${cameraProvider != null}"
    )
    stopObservingNightModeIndicator()
    stopObservingExtensionStrength()
    stopObservingExtensionCameraState()
    // Clear the stale surface request before unbinding so the viewfinder drops its dead texture.
    surfaceRequestMutable.value = null
    Log.dev("NightPreview", "teardownSession() cleared surfaceRequest -> null to avoid black-frame trap")
    currentRecording?.stop()
    currentRecording = null
    highSpeedRecording?.stop()
    highSpeedRecording = null
    // Reset recording/timer state: the session they belonged to is gone (e.g. app
    // backgrounded mid-recording via repeatOnLifecycle). Without this the timer keeps
    // ticking with isRecordingMutable=true while nothing records (phantom indicator), and a
    // pending shutter timer would fire capturePhoto() into the next session unprompted.
    // isCapturingMutable is also reset: a capture in flight across teardown never completes
    // (its session is gone), and a stuck true would deaden the shutter on resume.
    recordingTimerJob?.cancel()
    recordingTimerJob = null
    timerCountdownJob?.cancel()
    timerCountdownJob = null
    isRecordingMutable.value = false
    recordingPausedMutable.value = false
    recordingDurationSecMutable.value = 0
    timerCountdownMutable.value = 0
    isCapturingMutable.value = false
    stopLongExposureCountdown()
    try {
        imageAnalysis?.clearAnalyzer()
        Log.dev("NightPreview", "teardownSession() cleared analyzer previous=${desiredAnalyzer?.javaClass?.simpleName}")
    } catch (e: IllegalStateException) {
        Log.error("NightPreview", "teardownSession() clearAnalyzer failed (swallowed before)", e)
    }
    // desiredAnalyzer is deliberately kept: teardown runs on every rebind and the next bind
    // re-attaches it. Only the UI clears it, when the effect that owns the analyzer disposes.
    sessionLifecycleOwner?.destroy()
    sessionLifecycleOwner = null
    try {
        cameraProvider?.unbindAll()
        Log.dev("NightPreview", "teardownSession() unbindAll SUCCESS")
    } catch (e: IllegalStateException) {
        Log.error("NightPreview", "teardownSession() unbindAll FAILED (was hidden)", e)
    }
    boundCamera = null
    imageCapture = null
    imageAnalysis = null
    videoCapture = null
    boundVideoBitrateBps = null
    boundVideoQualityTier = null
    highSpeedVideoCapture = null
    cameraProvider = null
    photoSessionActiveMutable.value = false
    analysisStreamActiveMutable.value = false
    nightPreviewActiveMutable.value = false
    highSpeedActiveMutable.value = false
    videoSessionActiveMutable.value = false
    videoSnapshotSupportedMutable.value = false
    focusLockedMutable.value = false
    // Note: night-mode detection is intentionally NOT reset here. Teardown
    // runs on every night<->normal preview rebind, and resetting would clear
    // nightModeActive mid-swap and thrash the session. Explicit resets live in
    // switchCameraMode / flipCamera instead.
    //
    // Manual shutter/ISO are likewise NOT reset: they are no longer a transient preview
    // tweak — applyManualControls() pushes them onto the session, and
    // refreshCapabilities() re-asserts them after this teardown's rebind. Resetting
    // silently wiped the user's pro settings on every mode switch. The ISO stop list is
    // per-lens, so readManualControlRanges() re-probes it; the index is re-clamped there.
    clearMotionFrames()
}

internal fun CameraViewModel.av1SupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
    val caps = androidx.camera.video.Recorder.getVideoCapabilities(
        cameraInfo,
        android.media.MediaFormat.MIMETYPE_VIDEO_AV1
    )
    caps?.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)?.isNotEmpty() == true
} catch (e: IllegalStateException) {
    Log.status("VideoSession", "Could not query AV1 video capabilities", e)
    false
} catch (e: IllegalArgumentException) {
    Log.status("VideoSession", "Could not query AV1 video capabilities", e)
    false
}

internal fun CameraViewModel.hevcSupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
    val caps = androidx.camera.video.Recorder.getVideoCapabilities(
        cameraInfo,
        android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
    )
    caps?.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)?.isNotEmpty() == true
} catch (e: IllegalStateException) {
    Log.status("VideoSession", "Could not query HEVC video capabilities", e)
    false
} catch (e: IllegalArgumentException) {
    Log.status("VideoSession", "Could not query HEVC video capabilities", e)
    false
}

internal fun CameraViewModel.hlgSupportedByCamera(cameraInfo: androidx.camera.core.CameraInfo): Boolean = try {
    androidx.camera.video.Recorder.getVideoCapabilities(cameraInfo)
        .supportedDynamicRanges
        .contains(androidx.camera.core.DynamicRange.HLG_10_BIT)
} catch (e: IllegalStateException) {
    Log.status("VideoSession", "Could not query HLG10 dynamic-range support", e)
    false
} catch (e: IllegalArgumentException) {
    Log.status("VideoSession", "Could not query HLG10 dynamic-range support", e)
    false
}

/**
 * Stable fixed fps range for regular video.
 *
 * Previously we picked the absolute highest upper (e.g. 240) from
 * CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, which could be a variable range like
 * [30,60] or an HFR range like [120,120]. When AE varies the fps within a
 * variable range the HAL may switch sensor modes (full vs cropped), which shows
 * as a sudden zoom-in/out flicker. Only Slo-Mo uses the dedicated HFR path, so
 * it wasn't affected.
 *
 * Fix: only allow fixed ranges (lower == upper) and prefer 30fps. If no fixed
 * range exists we return null and let CameraX pick its default, which avoids the
 * crop-switch flicker.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.highestFpsRange(
    cameraInfo: androidx.camera.core.CameraInfo
): android.util.Range<Int>? = try {
    val ranges = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
        .getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: return null

    // Only fixed ranges avoid variable-fps sensor-mode switches that cause FOV flicker.
    val fixed = ranges.filter { it.lower == it.upper }
    if (fixed.isNotEmpty()) {
        // Prefer exact 30fps; Cinematic with EIS often can't do fixed 60 uncropped, so
        // stick to 30 for cinematic.
        if (cameraModeMutable.value == CameraMode.CINEMATIC) {
            fixed.firstOrNull { it.upper == VIDEO_FPS_STANDARD }
                ?: fixed.filter { it.upper <= VIDEO_FPS_STANDARD }.maxByOrNull { it.upper }
                ?: fixed.minByOrNull { kotlin.math.abs(it.upper - VIDEO_FPS_STANDARD) }
        } else {
            // For normal VIDEO/TIMELAPSE allow 60fps if available, but still fixed.
            fixed.firstOrNull { it.upper == VIDEO_FPS_STANDARD }
                ?: fixed.firstOrNull { it.upper == VIDEO_FPS_HIGH }
                ?: fixed.filter { it.upper <= VIDEO_FPS_STANDARD }.maxByOrNull { it.upper }
                ?: fixed.filter { it.upper <= VIDEO_FPS_HIGH }.maxByOrNull { it.upper }
                ?: fixed.minByOrNull { kotlin.math.abs(it.upper - VIDEO_FPS_STANDARD) }
        }
    } else {
        // No fixed ranges: don't force a variable range like [30,60] — return null so
        // CameraX chooses a stable default and avoids the flicker.
        null
    }
} catch (e: IllegalStateException) {
    Log.status("VideoSession", "Could not query supported frame-rate ranges", e)
    null
} catch (e: IllegalArgumentException) {
    Log.status("VideoSession", "Could not query supported frame-rate ranges", e)
    null
}

/**
 * Preferred video stabilization mode for Cinematic: preview-stabilization ("EIS") when the
 * device lists it, else on-mode, else null (unsupported → stabilization is skipped).
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.preferredStabilizationMode(cameraInfo: androidx.camera.core.CameraInfo): Int? = try {
    val modes = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
        .getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )?.toList() ?: emptyList()
    when {
        // Preview stabilization only exists from API 33; the SDK_INT guard keeps a vendor
        // HAL that reports the raw mode value on an older release from being taken at its word.
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            modes.contains(
                android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            ) -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        modes.contains(
            android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        ) -> android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        else -> null
    }
} catch (e: IllegalStateException) {
    Log.status("VideoSession", "Could not query video stabilization modes", e)
    null
} catch (e: IllegalArgumentException) {
    Log.status("VideoSession", "Could not query video stabilization modes", e)
    null
}

/** Preferred fixed video frame rates (fps). */
private const val VIDEO_FPS_STANDARD = 30
private const val VIDEO_FPS_HIGH = 60

/** Applies max-fps + (optional) stabilization capture options onto a Preview/VideoCapture builder. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun <T> CameraViewModel.applyVideoCaptureRequestOptions(
    builder: androidx.camera.core.ExtendableBuilder<T>,
    fpsRange: android.util.Range<Int>?,
    stabilizationMode: Int?
) {
    try {
        val extender = androidx.camera.camera2.interop.Camera2Interop.Extender(builder)
        if (fpsRange != null) {
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange
            )
        }
        if (stabilizationMode != null) {
            extender.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                stabilizationMode
            )
        }
    } catch (e: IllegalStateException) {
        Log.status("VideoSession", "Could not apply Camera2 video capture options", e)
    } catch (e: IllegalArgumentException) {
        Log.status("VideoSession", "Could not apply Camera2 video capture options", e)
    }
}

/** The pure lens-facing model for the currently selected facing. */
internal fun CameraViewModel.currentLensFacing(): com.vayunmathur.camera.domain.LensFacing =
    if (lensFacingMutable.value == CameraSelector.LENS_FACING_FRONT) {
        com.vayunmathur.camera.domain.LensFacing.FRONT
    } else {
        com.vayunmathur.camera.domain.LensFacing.BACK
    }

private fun FlashMode.toFlashSetting(): com.vayunmathur.camera.domain.FlashSetting = when (this) {
    FlashMode.ON -> com.vayunmathur.camera.domain.FlashSetting.ON
    FlashMode.OFF -> com.vayunmathur.camera.domain.FlashSetting.OFF
    FlashMode.AUTO -> com.vayunmathur.camera.domain.FlashSetting.AUTO
}

/** How the next still will be illuminated for the bound lens + user setting. */
internal fun CameraViewModel.currentFlashResolution(): com.vayunmathur.camera.domain.FlashResolution =
    com.vayunmathur.camera.domain.FlashModePolicy.resolve(
        currentLensFacing(),
        hasFlashUnitMutable.value,
        flashModeMutable.value.toFlashSetting(),
    )

/**
 * The CameraX flash mode for the next still. A front lens with no flash
 * unit resolves ON/AUTO to [ImageCapture.FLASH_MODE_SCREEN] (display as
 * flash); a lens with a real unit keeps the hardware modes exactly as
 * before. CameraX rejects FLASH_MODE_SCREEN on a back camera, which the
 * policy can never produce (screen is front-only).
 */
fun CameraViewModel.getImageCaptureFlashMode(): Int =
    when (currentFlashResolution()) {
        com.vayunmathur.camera.domain.FlashResolution.SCREEN -> ImageCapture.FLASH_MODE_SCREEN
        com.vayunmathur.camera.domain.FlashResolution.NONE -> ImageCapture.FLASH_MODE_OFF
        com.vayunmathur.camera.domain.FlashResolution.HARDWARE -> when (flashModeMutable.value) {
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        }
    }

/**
 * The viewfinder-provided [ImageCapture.ScreenFlash] to attach to a still
 * capture, or null unless the resolution is SCREEN. Null also clears a
 * stale instance when flipping back to a hardware-flash lens.
 */
internal fun CameraViewModel.getScreenFlashForCapture(): ImageCapture.ScreenFlash? =
    if (currentFlashResolution() == com.vayunmathur.camera.domain.FlashResolution.SCREEN) {
        screenFlash
    } else {
        null
    }

/**
 * Best-effort unbind used inside bind-retry catch blocks: logs (with [tag]) instead of
 * throwing so the retry ladder keeps walking the lens list.
 */
internal fun unbindQuietly(provider: ProcessCameraProvider, tag: String = "NightPreview") {
    try {
        provider.unbindAll()
    } catch (e: IllegalStateException) {
        Log.error(tag, "unbindAll in catch FAILED (hidden)", e)
    } catch (e: IllegalArgumentException) {
        Log.error(tag, "unbindAll in catch FAILED (hidden)", e)
    }
}
