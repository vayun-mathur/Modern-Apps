package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.camera.domain.PreviewExposurePolicy

fun CameraViewModel.setExposureTimeIndex(index: Int) {
    exposureTimeIndexMutable.value = index.coerceIn(0, CameraViewModel.EXPOSURE_TIME_STOPS.lastIndex)
    applyManualControls()
}

/** ISO index 0 == Auto; otherwise a 1-based index into [isoStopsMutable]. */
fun CameraViewModel.setManualIsoIndex(index: Int) {
    manualIsoIndexMutable.value = index.coerceIn(0, isoStopsMutable.value.size)
    applyManualControls()
}

/** The manual ISO for the current index, or null when set to Auto. */
internal fun CameraViewModel.manualIso(): Int? =
    manualIsoIndexMutable.value.takeIf { it > 0 }?.let { isoStopsMutable.value.getOrNull(it - 1) }

/** True when both shutter and ISO are on Auto (no manual exposure). */
internal fun CameraViewModel.isExposureAuto(): Boolean =
    exposureTimeIndexMutable.value == 0 && manualIsoIndexMutable.value == 0

/**
 * The current manual exposure split into preview vs still (see [PreviewExposurePolicy]),
 * or null when shutter and ISO are both Auto. The un-set half of a half-manual exposure
 * is seeded from the last auto-converged value (or a sensible default), exactly as the
 * still has always been seeded; only the preview half is capped/brightened.
 */
internal fun CameraViewModel.currentExposureSplit(): PreviewExposurePolicy.SplitExposure? {
    val manualShutter = CameraViewModel.EXPOSURE_TIME_STOPS[exposureTimeIndexMutable.value].nanos
    val manualIso = manualIso()
    if (manualShutter == null && manualIso == null) return null
    val stillNanos = manualShutter ?: lastAeExposureNanos ?: 16_666_667L // ~1/60s
    val stillIso = manualIso ?: lastAeIso ?: isoStopsMutable.value.getOrNull(isoStopsMutable.value.size / 2) ?: 400
    return PreviewExposurePolicy.split(stillNanos, stillIso, isoStopsMutable.value.maxOrNull())
}

/** True when the next still needs its full exposure applied at capture time. */
internal fun CameraViewModel.stillExposureOverrideRequired(): Boolean =
    currentExposureSplit()?.stillOverrideRequired == true

/**
 * Rebuilds a single [CaptureRequestOptions] from the current manual exposure/ISO state and
 * pushes it to the bound camera. This drives the LIVE (repeating) request shared by preview
 * and analysis, so it carries only the preview half of [currentExposureSplit]: a long manual
 * shutter is capped at 1/30s with a compensating ISO boost, keeping the viewfinder smooth —
 * the full still exposure is applied transiently at capture time instead (see
 * CameraSingleCapture). When on Auto the options are cleared, reverting to CameraX's default
 * auto behavior (including tap-to-focus). Called on every manual-control change and
 * re-applied after a session rebind.
 */
fun CameraViewModel.applyManualControls() {
    val session = activeCamera2Session ?: return
    // Seed the un-set half of a half-manual exposure from the session's
    // latest AE-converged preview values (as the interop path seeded
    // from its CaptureResult snapshots).
    val (aeNs, aeIso) = session.latestAeSnapshot()
    if (aeNs != null) lastAeExposureNanos = aeNs
    if (aeIso != null) lastAeIso = aeIso
    // Only the preview half goes on the live request: a long manual
    // shutter is capped at 1/30s with a compensating ISO boost, keeping
    // the viewfinder smooth; stills resolve the same state through
    // StillCapturePlanner (manual wins over AE/flash modes there).
    val split = currentExposureSplit()
    session.setManualExposure(split?.previewNanos, split?.previewIso)
    session.setPostProcessing(
        noiseReductionEnabledMutable.value,
        edgeSharpeningEnabledMutable.value,
    )
}

/** Reads the bound sensor's ISO range → stop list for the manual ISO control. */
internal fun CameraViewModel.readManualControlRanges() {
    val session = activeCamera2Session ?: run {
        Log.status("NightPreview", "readManualControlRanges() no live Camera2 session, returning")
        return
    }
    val isoRange = session.characteristics.sensitivityRange
    isoStopsMutable.value = if (isoRange != null) {
        listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
            .filter { it in isoRange.lower..isoRange.upper }
            .ifEmpty { listOf(isoRange.lower, isoRange.upper) }
    } else {
        Log.status(
            "NightPreview",
            "readManualControlRanges() isoRange null, emitting emptyList -> ISO bar notAvailable",
        )
        emptyList()
    }
    // The stop list is per-lens: a new lens can be shorter, so re-clamp the persisted
    // index instead of pointing past the end (ISO bar read getOrNull -> blank label).
    manualIsoIndexMutable.value = manualIsoIndexMutable.value.coerceIn(0, isoStopsMutable.value.size)
}
