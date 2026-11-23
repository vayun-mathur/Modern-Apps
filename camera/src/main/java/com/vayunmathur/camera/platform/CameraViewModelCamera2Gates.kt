package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log

/**
 * Camera2 bind-failure handling for [CameraViewModel]. Camera2 is the
 * only engine: a failure latches one-shot for its bind key and surfaces
 * the bind-failed error UI with a manual retry (which clears the
 * latch), instead of switching engines.
 */

/**
 * Records a Camera2 preview configure/bind failure. First failure for
 * this bind key latches (returns true) and the UI shows the retryable
 * bind error; repeat failures for the same key are ignored.
 */
fun CameraViewModel.onCamera2PreviewFailed(cameraId: String): Boolean {
    // Key on the CURRENT selected lens, not the (possibly stale) id the
    // failing composable captured: the startup seed is reconciled to
    // the enumerated lens in the background, and the UI builds its key
    // from the current selection — the latch must agree or it never
    // reads as latched.
    val keyId = selectedLensMutable.value?.logicalCameraId
        ?.takeIf { it.isNotEmpty() } ?: cameraId
    val key = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch
        .bindKey(keyId, cameraModeMutable.value)
    if (!camera2FallbackLatch.recordFailure(key)) return false
    camera2FallbackBindKeyMutable.value = key
    sessionBindFailedMutable.value = true
    Log.status("Camera2Preview", "Camera2 bind failed for $key; showing retryable bind error")
    return true
}

/** Clears the fallback latch (manual retry / new bind). */
fun CameraViewModel.clearCamera2Fallback() {
    camera2FallbackLatch.reset()
    camera2FallbackBindKeyMutable.value = null
}

/**
 * Records a Camera2 slo-mo preview/recording failure. First failure for
 * this bind key latches (returns true) and surfaces the retryable bind
 * error; repeat failures for the same key are ignored so a failing
 * device cannot loop.
 */
fun CameraViewModel.onCamera2SloMoFailed(cameraId: String): Boolean {
    val keyId = selectedLensMutable.value?.logicalCameraId
        ?.takeIf { it.isNotEmpty() } ?: cameraId
    val key = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch
        .bindKey(keyId, cameraModeMutable.value)
    if (!camera2SloMoFallbackLatch.recordFailure(key)) return false
    camera2SloMoFallbackBindKeyMutable.value = key
    sessionBindFailedMutable.value = true
    Log.status("SloMo", "Camera2 slo-mo failed for $key; showing retryable bind error")
    return true
}

/** Clears the slo-mo latch (manual retry / new bind). */
fun CameraViewModel.clearCamera2SloMoFallback() {
    camera2SloMoFallbackLatch.reset()
    camera2SloMoFallbackBindKeyMutable.value = null
}

/** Registers the live slo-mo high-speed session (null when it leaves composition). */
internal fun CameraViewModel.registerCamera2HighSpeedSession(
    session: com.vayunmathur.camera.platform.camera2.Camera2HighSpeedSession?,
) {
    activeCamera2HighSpeedSession = session
}

/**
 * Records a Camera2 NIGHT extension preview configure/open failure for
 * [cameraId]: latches this bind key so the UI stops composing the
 * extension preview and serves the regular Camera2 preview instead.
 * Does NOT write the persisted 7-day failure cache — a probe/configure
 * miss here must not suppress the extension at capture time; only a
 * real capture failure does that (see `tryCaptureCamera2NightExtension`).
 */
fun CameraViewModel.onCamera2NightPreviewFailed(cameraId: String) {
    // Key on the CURRENT selected lens, not the (possibly stale) id the
    // failing composable captured — same contract as onCamera2PreviewFailed.
    val keyId = selectedLensMutable.value?.logicalCameraId
        ?.takeIf { it.isNotEmpty() } ?: cameraId
    val key = com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch
        .bindKey(keyId, cameraModeMutable.value)
    if (camera2NightFallbackBindKeyMutable.value == key) return
    camera2NightFallbackBindKeyMutable.value = key
    Log.status("Camera2Night", "Camera2 extension preview failed for $key; regular preview serves this session")
}

/** Clears the Camera2 night latch (new lens/mode or manual retry). */
fun CameraViewModel.clearCamera2NightFallback() {
    camera2NightFallbackBindKeyMutable.value = null
}
