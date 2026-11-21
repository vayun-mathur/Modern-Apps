package com.vayunmathur.camera.platform

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

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

@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.camera2ControlOrNull(): androidx.camera.camera2.interop.Camera2CameraControl? = try {
    boundCamera?.cameraControl?.let {
        androidx.camera.camera2.interop.Camera2CameraControl.from(it)
    }
} catch (e: IllegalArgumentException) {
    Log.status("CameraViewModel", "Camera2 control unavailable", e)
    null
} catch (e: NullPointerException) {
    // The interop null-checks its implementation internally; a control with
    // no Camera2 implementation is "unavailable", not a crash.
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

/** AE-off + [nanos]/[iso] capture options for one still, shared by the capture paths. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun stillCaptureRequestOptions(
    nanos: Long,
    iso: Int?
): androidx.camera.camera2.interop.CaptureRequestOptions {
    val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
        .setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        .setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, nanos
        )
    if (iso != null) {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso
        )
    }
    return builder.build()
}

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
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
fun CameraViewModel.applyManualControls() {
    val cam2 = camera2ControlOrNull() ?: return
    val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

    // Manual exposure / ISO with linkage: if either is manual, lock AE off and set both.
    // Only the preview half goes on this live request (long shutters capped, ISO boosted
    // to compensate); the still half is applied at capture time.
    val split = currentExposureSplit()
    if (split != null) {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, split.previewNanos
        )
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, split.previewIso
        )
    }

    // Post-processing toggles (issue #662): OFF explicitly disables the HAL's noise
    // reduction / edge sharpening. ON now explicitly requests the high-quality
    // modes instead of leaving the keys unset, where the HAL default may be
    // FAST. Tonemap joins the high-quality request when either toggle is on,
    // so turning both off does not force a high-quality tonemap either.
    if (noiseReductionEnabledMutable.value) {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
            android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        )
    } else {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
            android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF
        )
    }
    if (edgeSharpeningEnabledMutable.value) {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.EDGE_MODE,
            android.hardware.camera2.CameraMetadata.EDGE_MODE_HIGH_QUALITY
        )
    } else {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.EDGE_MODE,
            android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF
        )
    }
    if (noiseReductionEnabledMutable.value || edgeSharpeningEnabledMutable.value) {
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.TONEMAP_MODE,
            android.hardware.camera2.CameraMetadata.TONEMAP_MODE_HIGH_QUALITY
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
@OptIn(ExperimentalCamera2Interop::class)
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
