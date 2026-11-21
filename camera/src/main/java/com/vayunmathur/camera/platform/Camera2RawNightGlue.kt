package com.vayunmathur.camera.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.data.saveProcessedStill
import com.vayunmathur.camera.data.setLastCaptureUri
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.DngMetadataAssembler
import com.vayunmathur.camera.platform.camera2.RawNightInput
import com.vayunmathur.camera.platform.camera2.RawNightMerger
import com.vayunmathur.camera.platform.camera2.RawNightPolicy
import com.vayunmathur.camera.platform.camera2.RawRingGate
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera2 depth Phase 2: RAW night glue between the gated dual-ring
 * [Camera2Session] and the existing night routing.
 *
 * Decision (see [RawNightPolicy]): RAW takes the night shutter only when
 * the gate is on, RAW night was explicitly selected, the facade proves
 * RAW, and the RAW ring is live. Else this returns false and the existing
 * routing (vendor extension, then YUV burst) runs exactly as before.
 *
 * On a RAW burst, frames go through the Rust linear merge and the result
 * lands via `saveProcessedStill` — the same `StillEncoder` landing as
 * every other night shot. No DNG sidecar is written: metadata assembly
 * is validated by [DngMetadataAssembler] and logged, but `DngCreator`
 * needs live characteristics/results the ring does not retain (see
 * `DngMetadata.kt`), so claiming a DNG here would be dishonest.
 */
internal fun CameraViewModel.tryCaptureCamera2RawNight(): Boolean {
    val session = activeCamera2Session ?: return false
    val snapshot = session.characteristics
    val input = RawNightInput(
        rawGateOn = RawRingGate.ENABLED && camera2RawNightEnabledMutable.value,
        explicitRawSelected = camera2RawNightEnabledMutable.value,
        mode = cameraModeMutable.value,
        nightRoute = true,
        rawSupported = session.rawSupported,
        hasRawRingOutput = session.hasRawRingOutput,
        dngMetadataComplete = DngMetadataAssembler.assemble(snapshot) != null,
    )
    if (!RawNightPolicy.usesRawNight(input)) {
        Log.debug(
            "Camera2RawNight",
            "RAW night skipped: ${RawNightPolicy.exclusionReason(input)}; " +
                "dngMissing=${DngMetadataAssembler.missingRequirements(snapshot)}",
        )
        return false
    }
    emitShutterFeedback()
    session.captureRawBurst(
        count = RawNightMerger.RAW_BURST_COUNT,
        callback = object : Camera2Session.RawBurstCallback {
            override fun onRawBurst(
                frames: List<com.vayunmathur.camera.platform.camera2.RawHeldFrame>,
                metas: List<com.vayunmathur.camera.platform.camera2.RingFrameMeta>,
            ) {
                viewModelScope.launch {
                    val uri = withContext(Dispatchers.Default) {
                        try {
                            val merged = RawNightMerger.merge(
                                frames, snapshot,
                                previewMetadataFor = { session.previewMetadataFor(it) },
                            )
                            if (merged == null) {
                                null
                            } else {
                                try {
                                    withContext(Dispatchers.IO) {
                                        saveProcessedStill(merged, 0)?.uri
                                    }
                                } finally {
                                    merged.recycle()
                                }
                            }
                        } finally {
                            frames.forEach { runCatching { it.close() } }
                        }
                    }
                    isCapturingMutable.value = false
                    if (uri != null) {
                        setLastCaptureUri(uri)
                        stopLongExposureCountdown()
                    } else {
                        // RAW merge failed after claiming the shutter:
                        // hand the shot back to the existing night paths.
                        Log.status("Camera2RawNight", "RAW merge failed; falling back to YUV night")
                        captureNightPhotoCustom()
                    }
                }
            }

            override fun onRawBurstFailed(message: String) {
                Log.status("Camera2RawNight", "RAW burst failed: $message; falling back")
                isCapturingMutable.value = false
                captureNightPhotoCustom()
            }
        },
    )
    return true
}
