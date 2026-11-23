package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import kotlinx.coroutines.delay

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
    // A manual retry is a fresh bind attempt: Camera2 may be tried once again.
    clearCamera2Fallback()
    sessionBindRetryMutable.value += 1
}

/**
 * Tears down the transient capture/recording state for a rebind. The
 * Camera2 sessions themselves are owned by their preview composables
 * (Camera2Preview / Camera2NightPreview / Camera2SloMoPreview), whose
 * disposal closes them; this only resets the shared ViewModel state a
 * dead session must not leave behind (phantom recording indicators,
 * stuck capturing flags, timers, motion frames).
 */
fun CameraViewModel.teardownSession() {
    sessionTeardownCount++
    // Stop any in-flight Camera2 recordings: a rebind across a live
    // recording would otherwise leave its engine owning a closed camera.
    if (camera2RecordingState.engine != null) {
        stopCamera2Recording()
    }
    if (camera2RecordingState.sloMoControl != null && isRecordingMutable.value) {
        stopCamera2SloMoRecording()
    }
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
    // Manual shutter/ISO are likewise NOT reset: applyManualControls()
    // pushes them onto the live Camera2 session and readManualControlRanges()
    // re-asserts them after a rebind. Resetting silently wiped the user's pro
    // settings on every mode switch.
    clearMotionFrames()
    Log.dev("CameraSessions", "teardownSession() #${sessionTeardownCount}")
}

/** The pure lens-facing model for the currently selected facing. */
internal fun CameraViewModel.currentLensFacing(): com.vayunmathur.camera.domain.LensFacing =
    if (lensFacingMutable.value == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT) {
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
