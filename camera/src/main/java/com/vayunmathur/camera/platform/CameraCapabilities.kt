package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.camera.domain.LensSelectionLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-lens capability snapshot refreshed after every Camera2 bind, so the
 * UI (zoom pills, flash availability, ISO stops, night affordances)
 * reflects the lens that actually bound rather than the previous one.
 */
data class LensCapabilities(
    val lensId: String?,
    val minZoom: Float,
    val maxZoom: Float,
    val zoomLevels: List<Pair<String, Float>>,
    val hasFlashUnit: Boolean,
    val isoStops: List<Int>,
    val exposureCompRange: ClosedRange<Float>?,
    val nightExtensionUsable: Boolean,
)

/**
 * Re-probes everything the UI derives from the live Camera2 session's
 * characteristics snapshot and publishes it on the ViewModel. No-op when
 * no Camera2 session is registered yet.
 */
suspend fun CameraViewModel.refreshCapabilities(lensId: String?) {
    val snapshot = activeCamera2Session?.characteristics ?: return
    val minZoom = snapshot.minZoomRatio
    val maxZoom = snapshot.maxZoomRatio
    updateZoomLevels(minZoom, maxZoom)
    restoreZoom(minZoom, maxZoom)

    readManualControlRanges()
    reapplyCaptureControls()
    try {
        enableTorch(torchEnabledMutable.value)
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Could not re-apply torch", e)
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Could not re-apply torch", e)
    }

    val hasFlash = snapshot.hasFlashUnit
    hasFlashUnitMutable.value = hasFlash

    val expRange = snapshot.exposureCompensationEvRange?.let { it.lower..it.upper }
    if (expRange != null) exposureCompRangeMutable.value = expRange

    withContext(Dispatchers.Default) {
        refreshNightExtensionUsable(cameraModeMutable.value)
    }

    lensCapabilitiesMutable.value = LensCapabilities(
        lensId = lensId ?: snapshot.cameraId,
        minZoom = minZoom,
        maxZoom = maxZoom,
        zoomLevels = LensSelectionLogic.buildZoomLevels(minZoom, maxZoom),
        hasFlashUnit = hasFlash,
        isoStops = isoStopsMutable.value,
        exposureCompRange = expRange ?: lensCapabilitiesMutable.value?.exposureCompRange,
        nightExtensionUsable = nightExtensionUsableMutable.value,
    )
    Log.dev("LensSelector", "Refreshed capabilities lens=$lensId zoom=[$minZoom,$maxZoom] flash=$hasFlash")
}

/**
 * Re-asserts manual shutter/ISO and exposure compensation onto the fresh
 * bind: the session effect only pushes them on value change, so without
 * this a rebind silently drops them.
 */
private fun CameraViewModel.reapplyCaptureControls() {
    try {
        applyManualControls()
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Could not re-apply manual controls", e)
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Could not re-apply manual controls", e)
    }
    try {
        applyExposureCompensation(exposureCompensationMutable.value)
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Could not re-apply exposure compensation", e)
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Could not re-apply exposure compensation", e)
    }
}
