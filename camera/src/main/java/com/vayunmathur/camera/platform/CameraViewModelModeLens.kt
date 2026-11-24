package com.vayunmathur.camera.platform


/**
 * Mode and lens actions for [CameraViewModel]: mode switches, lens flip
 * and physical-lens selection. All three rebind the session, so all three
 * are gated while a recording is in flight (the UI explains why). Moved
 * verbatim from the ViewModel as extensions; signatures and behaviour
 * are unchanged.
 */

fun CameraViewModel.switchCameraMode(newMode: CameraMode) {
    // Mid-recording mode switches rebind the session, whose teardown stops the
    // active recording. Unlike the persisted bind-time settings they cannot be
    // deferred (a mode jump on stop would surprise), so the switch is gated
    // while recording; the UI explains why. Same-mode taps stay harmless.
    if (isRecordingMutable.value && newMode != cameraModeMutable.value) return
    val previousMode = cameraModeMutable.value
    // A pending shutter timer belongs to the old mode: firing capturePhoto() into the
    // new session would capture unprompted (and with the wrong use cases bound).
    if (newMode != cameraModeMutable.value) {
        cancelTimerCountdown()
        resetNightModeDetection()
    }
    // Slo-Mo is back-camera only; enforce it when entering Slo-Mo. The
    // selected lens must move too, not just the facing flag: Camera2
    // preview holders key on the selected lens's facing, and a stale
    // front lens there builds a front high-speed session that can
    // never configure (the CameraX setup masked this by re-seeking
    // the back lens at bind time).
    if (newMode == CameraMode.SLOW_MO) {
        if (lensFacingMutable.value != android.hardware.camera2.CameraMetadata.LENS_FACING_BACK) {
            lensFacingMutable.value = android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
            selectedLensMutable.value =
                com.vayunmathur.camera.domain.LensSelectionLogic.filterByFacing(
                    availableLensesMutable.value,
                    com.vayunmathur.camera.domain.LensFacing.BACK,
                ).minByOrNull { it.fallbackPriority }
            selectedLensMutable.value?.let { persistStartupLens(it) }
            resetNightModeDetection()
        }
    }
    cameraModeMutable.value = newMode
    // Zoom lock (PANORAMA/PHOTOSPHERE): apply 1x to the still-live
    // session immediately, so no frame is ever previewed/stitched at
    // the stored ratio in between the mode tap and the rebind (whose
    // restoreZoom re-asserts the same). Leaving a locked mode
    // re-applies the stored ratio — it was never overwritten while
    // locked, so the user's Photo/other-mode zoom comes back as-is.
    // With no live session both are no-ops and the next bind applies
    // the same rule via restoreZoom/registerCamera2Session.
    when {
        newMode.isZoomLockedTo1x() ->
            activeCamera2Session?.setZoomRatio(ZOOM_LOCK_RATIO)
        previousMode.isZoomLockedTo1x() && newMode != previousMode -> {
            val session = activeCamera2Session
            if (session != null) {
                restoreZoom(
                    session.characteristics.minZoomRatio,
                    session.characteristics.maxZoomRatio,
                )
            }
        }
    }
}

fun CameraViewModel.flipCamera() {
    // Disallow flipping while in Slo-Mo — it's back-camera only, true HFR.
    if (cameraModeMutable.value == CameraMode.SLOW_MO) return
    // A flip rebinds the session and teardown would stop an active recording
    // (the shutter row already hides flip while recording; this is the backstop).
    if (isRecordingMutable.value) return
    lensFacingMutable.value = if (lensFacingMutable.value == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK)
        android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT else android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
    // Preserve the lens family across the flip: e.g. UW back -> front lens,
    // tele back -> front lens (front has a single family, anchored at its default).
    val facing = if (lensFacingMutable.value == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK)
        com.vayunmathur.camera.domain.LensFacing.BACK
    else com.vayunmathur.camera.domain.LensFacing.FRONT
    selectedLensMutable.value =
        com.vayunmathur.camera.domain.LensSelectionLogic.filterByFacing(
            availableLensesMutable.value, facing
        ).minByOrNull { it.fallbackPriority }
    selectedLensMutable.value?.let { persistStartupLens(it) }
    resetNightModeDetection()
}

/**
 * Selects a physical lens within the current facing family. The session
 * rebind effect is keyed on [selectedLens], so this triggers a rebind with
 * the filtered selector plus a capability refresh. Unknown lenses are
 * ignored so a stale UI entry can never crash a bind.
 */
fun CameraViewModel.selectLens(lens: com.vayunmathur.camera.domain.PhysicalLens) {
    // A lens switch rebinds the session and teardown would stop an active
    // recording; gated while recording (the lens bar explains why in the UI).
    if (isRecordingMutable.value) return
    if (lens.facing != (
        if (lensFacingMutable.value == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK)
            com.vayunmathur.camera.domain.LensFacing.BACK
        else com.vayunmathur.camera.domain.LensFacing.FRONT
        )
    ) {
        return
    }
    if (lens !in availableLensesMutable.value) return
    selectedLensMutable.value = lens
    persistStartupLens(lens)
    resetNightModeDetection()
}
