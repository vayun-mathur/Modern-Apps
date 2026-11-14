package com.vayunmathur.camera.util

import com.vayunmathur.library.log.Log

fun CameraViewModel.setExposureTimeIndex(index: Int) {
    exposureTimeIndexMutable.value = index.coerceIn(0, CameraViewModel.EXPOSURE_TIME_STOPS.lastIndex)
    applyManualControls()
}

/** ISO index 0 == Auto; otherwise a 1-based index into [isoStopsMutable]. */
fun CameraViewModel.setManualIsoIndex(index: Int) {
    manualIsoIndexMutable.value = index.coerceIn(0, isoStopsMutable.value.size)
    applyManualControls()
}

@Suppress("DEPRECATION")
internal fun CameraViewModel.camera2ControlOrNull(): androidx.camera.camera2.interop.Camera2CameraControl? = try {
    boundCamera?.cameraControl?.let {
        androidx.camera.camera2.interop.Camera2CameraControl.from(it)
    }
} catch (e: IllegalArgumentException) {
    Log.status("CameraViewModel", "Camera2 control unavailable", e)
    null
}

/** The manual ISO for the current index, or null when set to Auto. */
internal fun CameraViewModel.manualIso(): Int? =
    manualIsoIndexMutable.value.takeIf { it > 0 }?.let { isoStopsMutable.value.getOrNull(it - 1) }

/** True when both shutter and ISO are on Auto (no manual exposure). */
internal fun CameraViewModel.isExposureAuto(): Boolean =
    exposureTimeIndexMutable.value == 0 && manualIsoIndexMutable.value == 0

/**
 * Rebuilds a single [CaptureRequestOptions] from the current manual exposure/ISO state and
 * pushes it to the bound camera, affecting the live preview and subsequent stills. When on Auto
 * the options are cleared, reverting to CameraX's default auto behavior (including tap-to-focus).
 * Called on every manual-control change and re-applied after a session rebind.
 */
@Suppress("DEPRECATION")
fun CameraViewModel.applyManualControls() {
    val cam2 = camera2ControlOrNull() ?: return
    val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

    // Manual exposure / ISO with linkage: if either is manual, lock AE off and set both,
    // seeding the un-set one from the last auto-converged value (or a sensible default).
    val manualShutter = CameraViewModel.EXPOSURE_TIME_STOPS[exposureTimeIndexMutable.value].nanos
    val manualIso = manualIso()
    if (manualShutter != null || manualIso != null) {
        val exposure = manualShutter ?: lastAeExposureNanos ?: 16_666_667L // ~1/60s
        val iso = manualIso ?: lastAeIso ?: isoStopsMutable.value.getOrNull(isoStopsMutable.value.size / 2) ?: 400
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, exposure
        )
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso
        )
    }

    // Post-processing toggles (issue #662): OFF explicitly disables the HAL's noise
    // reduction / edge sharpening. When enabled the keys are left unset so the HAL
    // keeps its default behavior.
    if (!noiseReductionEnabledMutable.value) {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
            android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF
        )
    }
    if (!edgeSharpeningEnabledMutable.value) {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.EDGE_MODE,
            android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF
        )
    }

    try {
        // An empty options set clears any previously-applied manual 3A → full auto.
        cam2.setCaptureRequestOptions(builder.build())
    } catch (e: IllegalStateException) {
        Log.status("CameraViewModel", "Failed to apply manual controls", e)
    } catch (e: IllegalArgumentException) {
        Log.status("CameraViewModel", "Failed to apply manual controls", e)
    }
}

/** Reads the bound sensor's ISO range → stop list for the manual ISO control. */
@Suppress("DEPRECATION")
internal fun CameraViewModel.readManualControlRanges() {
    Log.dev(
        "NightPreview",
        "readManualControlRanges() called bound=${boundCamera != null} " +
            "thread=${Thread.currentThread().name}"
    )
    val cam = boundCamera ?: run {
        Log.status("NightPreview", "readManualControlRanges() no bound camera, returning")
        return
    }
    try {
        val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
        val isoRange = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )
        Log.dev("NightPreview", "readManualControlRanges() isoRange=$isoRange")
        isoStopsMutable.value = if (isoRange != null) {
            val filtered = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
                .filter { it in isoRange.lower..isoRange.upper }
                .ifEmpty { listOf(isoRange.lower, isoRange.upper) }
            Log.dev("NightPreview", "readManualControlRanges() filtered stops=$filtered")
            filtered
        } else {
            Log.status(
                "NightPreview",
                "readManualControlRanges() isoRange null, emitting emptyList -> ISO bar notAvailable",
            )
            emptyList()
        }
        // The stop list is per-lens: a new lens can be shorter, so re-clamp the persisted
        // index instead of pointing past the end (ISO bar read getOrNull → blank label).
        manualIsoIndexMutable.value = manualIsoIndexMutable.value.coerceIn(0, isoStopsMutable.value.size)
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "readManualControlRanges() FAILED (was Warn, hidden) – " +
                "could affect ISO bar + manual controls",
            e
        )
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "readManualControlRanges() FAILED (was Warn, hidden) – " +
                "could affect ISO bar + manual controls",
            e
        )
    }
}
