package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import androidx.camera.core.Camera
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.applyExposureCompensation
import com.vayunmathur.camera.platform.applyManualControls
import com.vayunmathur.camera.platform.readManualControlRanges
import com.vayunmathur.camera.platform.refreshNightExtensionUsable
import com.vayunmathur.camera.platform.restoreZoom
import com.vayunmathur.camera.platform.updateZoomLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-lens capability snapshot refreshed after every bind, so the UI (zoom
 * pills, flash availability, ISO stops, night/slo-mo affordances) reflects the
 * lens that actually bound rather than the previous one.
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
 * Re-probes everything the UI derives from the bound camera and publishes it
 * on the ViewModel. Reuses the existing per-bind probes
 * ([updateZoomLevels]/[restoreZoom], [readManualControlRanges],
 * [observeNightModeIndicator], [refreshNightExtensionUsable]) and adds the
 * missing coverage: flash-unit availability per lens.
 *
 * Call on the main thread (LiveData `observeForever` expects it); the heavier
 * extension probe hops to Dispatchers.Default like the UI's launcher effect.
 */
suspend fun CameraViewModel.refreshCapabilities(bound: Camera, lensId: String?) {
    val (minZoom, maxZoom) = readZoomBounds(bound)
    updateZoomLevels(minZoom, maxZoom)
    restoreZoom(minZoom, maxZoom)

    readManualControlRanges()
    reapplyCaptureControls()
    reapplyTorch(bound)

    val hasFlash = readFlashUnit(bound)
    hasFlashUnitMutable.value = hasFlash

    val expRange = readExposureRange(bound)
    if (expRange != null) exposureCompRangeMutable.value = expRange

    // Skip the night indicator here: the setup paths own that observation, and
    // re-observing the *extension* camera reports UNKNOWN/NOT_RECOMMENDED, which
    // fights the normal session's RECOMMENDED reading (engage/disengage loop —
    // see the note in setupNightPreviewSession).
    withContext(Dispatchers.Default) {
        refreshNightExtensionUsable(cameraModeMutable.value)
    }

    lensCapabilitiesMutable.value = LensCapabilities(
        lensId = lensId,
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

/** Zoom bounds off the bound camera; unity defaults when unreadable. */
private fun CameraViewModel.readZoomBounds(bound: Camera): Pair<Float, Float> {
    val zs = try {
        bound.cameraInfo.zoomState.value
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Could not read zoom state", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Could not read zoom state", e)
        null
    }
    return (zs?.minZoomRatio ?: 1f) to (zs?.maxZoomRatio ?: 1f)
}

/**
 * Re-asserts manual shutter/ISO and exposure compensation onto the fresh bind: the
 * session effect only pushes them on value change, so without this a rebind silently
 * drops them (video mode never applied them at all).
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

/**
 * Re-asserts the UI's torch state onto the fresh bind. The session effect
 * only calls `enableTorch` when `torchEnabled` changes, so a mode/lens
 * rebind (which resets the hardware torch) left the UI lit with the torch
 * off until the user toggled it twice.
 */
private fun CameraViewModel.reapplyTorch(bound: Camera) {
    try {
        bound.cameraControl.enableTorch(torchEnabledMutable.value)
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Could not re-apply torch", e)
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Could not re-apply torch", e)
    } catch (e: NullPointerException) {
        // Fake controls in unit tests return a null future for enableTorch;
        // a real bind always returns one, so this is "unavailable", not a crash.
        Log.status("LensSelector", "Could not re-apply torch", e)
    }
}

/** Flash-unit availability; keeps the previous value when unreadable. */
private fun CameraViewModel.readFlashUnit(bound: Camera): Boolean {
    return try {
        bound.cameraInfo.hasFlashUnit()
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Could not query flash unit", e)
        hasFlashUnitMutable.value
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Could not query flash unit", e)
        hasFlashUnitMutable.value
    }
}

/** Exposure-compensation range; null when unreadable. */
private fun CameraViewModel.readExposureRange(bound: Camera): ClosedRange<Float>? {
    return try {
        val r = bound.cameraInfo.exposureState.exposureCompensationRange
        r.lower.toFloat()..r.upper.toFloat()
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Could not query exposure-compensation range", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Could not query exposure-compensation range", e)
        null
    }
}
