package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Zoom-out floor for offering the wide-angle zoom-bar entry. */
private const val WIDE_ZOOM_ENTRY_MAX = 0.95f

/** Tele zoom-bar entries and the tolerance for including them at the range edge. */
private val TELE_ZOOM_LEVELS = listOf(2f, 5f)
private const val TELE_ZOOM_TOLERANCE = 0.05f

fun CameraViewModel.setQrResult(text: String?) {
    qrResultMutable.value = text
}

/**
 * Syncs an already-applied [ratio] to the displayed value (used by the zoom
 * bar). Kept for callers that applied zoom themselves; the preview pinch
 * path goes through [setZoomRatio], which applies to the session itself.
 */
fun CameraViewModel.onViewfinderZoomRatio(ratio: Float) {
    // Zoom-locked modes (PANORAMA/PHOTOSPHERE): a pinch that already
    // reached the camera is forced straight back to 1x, and the stored
    // ratio is left alone so leaving the mode restores it.
    if (cameraModeMutable.value.isZoomLockedTo1x()) {
        activeCamera2Session?.setZoomRatio(ZOOM_LOCK_RATIO)
        return
    }
    zoomRatioMutable.value = ratio
    viewModelScope.launch { ds.setString("camera_zoom_ratio", ratio.toString()) }
}

/** Live device zoom range from the active session's characteristics; 1x-only when no session. */
fun CameraViewModel.currentZoomRange(): Pair<Float, Float> {
    val range = activeCamera2Session?.characteristics?.zoomRatioRange
    return if (range != null) range.lower to range.upper else 1f to 1f
}

fun CameraViewModel.setZoomRatio(ratio: Float) {
    // Zoom-locked modes ignore the request entirely (the zoom bar is
    // hidden there; this is the backstop for any other caller): apply
    // 1x to the session, never to the stored/persisted ratio.
    if (cameraModeMutable.value.isZoomLockedTo1x()) {
        activeCamera2Session?.setZoomRatio(ZOOM_LOCK_RATIO)
        return
    }
    val session = activeCamera2Session
    val range = session?.characteristics?.zoomRatioRange
    val clamped = if (range != null) ratio.coerceIn(range.lower, range.upper) else ratio
    zoomRatioMutable.value = clamped
    viewModelScope.launch { ds.setString("camera_zoom_ratio", clamped.toString()) }
    session?.setZoomRatio(clamped)
}

/**
 * Restore the previously selected zoom after a (re)bind instead of snapping back to the
 * hardware default. [zoomRatioMutable] holds the last value the user picked (kept in memory across
 * teardown/rebind and loaded from DataStore on process restart); clamp it to the new lens'
 * supported range and re-apply it to the camera. Fixes issue #631 (zoom reset on resume).
 */
internal fun CameraViewModel.restoreZoom(minZoom: Float, maxZoom: Float) {
    // Zoom-locked modes (PANORAMA/PHOTOSPHERE) bind at 1x regardless of
    // the stored ratio. The stored value is deliberately NOT clamped
    // into zoomRatioMutable here: it belongs to the non-locked modes
    // and must come back untouched when the user leaves the mode.
    if (cameraModeMutable.value.isZoomLockedTo1x()) {
        activeCamera2Session?.setZoomRatio(ZOOM_LOCK_RATIO)
        return
    }
    val desired = zoomRatioMutable.value.coerceIn(minZoom, maxZoom)
    zoomRatioMutable.value = desired
    activeCamera2Session?.setZoomRatio(desired)
}

fun CameraViewModel.updateZoomLevels(minZoom: Float, maxZoom: Float) {
    Log.dev(
        "NightPreview",
        "updateZoomLevels() min=$minZoom max=$maxZoom " +
            "nightPreviewActive=${nightPreviewActiveMutable.value} " +
            "photoActive=${photoSessionActiveMutable.value} currentRatio=${zoomRatioMutable.value} " +
            "thread=${Thread.currentThread().name}"
    )
    val levels = mutableListOf<Pair<String, Float>>()
    // Wide-angle entry only when the lens can actually zoom out past 1x.
    if (minZoom < WIDE_ZOOM_ENTRY_MAX) {
        levels.add(formatZoomLabel(minZoom) to minZoom)
    }
    levels.add("1x" to 1f)
    for (tele in TELE_ZOOM_LEVELS) {
        if (tele <= maxZoom + TELE_ZOOM_TOLERANCE) levels.add(formatZoomLabel(tele) to tele)
    }
    Log.dev(
        "NightPreview",
        "updateZoomLevels() emitting levels=$levels – if min=1f max=1f, only [1x] will show, " +
            "explaining 'all zoom levels also disappear'"
    )
    availableZoomLevelsMutable.value = levels
}

/**
 * Tap-to-focus entry for the overlay gesture layer: routes the tap to
 * the live Camera2 session's metering transform. Returns false when no
 * session is live (the tap is dropped, as before). [locked] keeps the
 * metered point until the next tap (the session holds AF/AE regions
 * until cleared or replaced — there is no auto-cancel timer on this
 * path, so a plain tap also persists; the focus ring UI reflects it).
 */
fun CameraViewModel.tapToFocusAt(xFraction: Float, yFraction: Float, locked: Boolean): Boolean {
    val session = activeCamera2Session ?: run {
        Log.status("TapToFocus", "focus requested with no live Camera2 session (locked=$locked); tap dropped")
        return false
    }
    session.tapToFocus(xFraction, yFraction)
    focusLockedMutable.value = locked
    return true
}

fun CameraViewModel.startFocusAndMeteringAt(xFraction: Float, yFraction: Float): Boolean {
    focusLockedMutable.value = false
    return tapToFocusAt(xFraction, yFraction, locked = false)
}

fun CameraViewModel.lockFocusAndMeteringAt(xFraction: Float, yFraction: Float): Boolean =
    tapToFocusAt(xFraction, yFraction, locked = true)

/** Returns metering to the session's default continuous AF/AE. */
internal fun CameraViewModel.fallbackToDefaultMetering() {
    activeCamera2Session?.clearMetering()
}

fun CameraViewModel.clearFocusLock() {
    activeCamera2Session?.clearMetering()
    focusLockedMutable.value = false
}

fun CameraViewModel.enableTorch(enabled: Boolean) {
    activeCamera2Session?.setTorch(enabled)
}

fun CameraViewModel.applyExposureCompensation(value: Float) {
    val session = activeCamera2Session ?: return
    // The slider is normalized to -1..1; scale each half against its own
    // end of the lens's EV range (as the CameraX index path did).
    val range = session.characteristics.exposureCompensationEvRange
    val ev = if (range != null) {
        if (value >= 0f) value * range.upper else -value * range.lower
    } else {
        value * 2f
    }
    session.setExposureCompEv(ev)
}

/**
 * Pushes the current flash mode onto the bound ImageCapture (runtime-mutable, no rebind).
 * The ScreenFlash is attached first (and cleared on hardware lenses) so a
 * FLASH_MODE_SCREEN capture never fires without its UI mechanism in place.
 */
/**
 * Flash mode is pushed onto the Camera2 session by the preview
 * composable (flashSetting parameter) and resolved per still by
 * StillCapturePlanner; this no-op keeps the legacy effect call sites
 * compiling until they are removed with the session layer.
 */
fun CameraViewModel.applyImageCaptureFlashMode() {
    activeCamera2Session?.setFlashSetting(
        when (flashModeMutable.value) {
            FlashMode.ON -> com.vayunmathur.camera.domain.FlashSetting.ON
            FlashMode.AUTO -> com.vayunmathur.camera.domain.FlashSetting.AUTO
            FlashMode.OFF -> com.vayunmathur.camera.domain.FlashSetting.OFF
        }
    )
}

/** Recording-timer tick (1s). */
private const val RECORDING_TIMER_TICK_MS = 1000L

/** Publishes whether the live session carries an analysis stream. */
internal fun CameraViewModel.onSessionBound() {
    analysisStreamActiveMutable.value = analysisSink != null
}

internal fun CameraViewModel.startRecordingTimer() {
    isRecordingMutable.value = true
    recordingPausedMutable.value = false
    recordingDurationSecMutable.value = 0
    recordingTimerJob = viewModelScope.launch {
        while (true) {
            delay(RECORDING_TIMER_TICK_MS)
            if (!recordingPausedMutable.value) recordingDurationSecMutable.value += 1
        }
    }
}

internal fun CameraViewModel.stopRecordingTimer() {
    isRecordingMutable.value = false
    recordingPausedMutable.value = false
    recordingTimerJob?.cancel()
    recordingDurationSecMutable.value = 0
}
