package com.vayunmathur.camera.platform

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.data.MediaStoreSaver
import com.vayunmathur.camera.data.saveProcessedStill
import com.vayunmathur.camera.data.saveStillBytes
import com.vayunmathur.camera.data.setLastCaptureUri
import com.vayunmathur.camera.domain.FlashSetting
import com.vayunmathur.camera.domain.StillFormat
import com.vayunmathur.camera.domain.StillFormatRouting
import com.vayunmathur.camera.domain.UltraHdrIntentPolicy
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CapturedStill
import com.vayunmathur.camera.platform.camera2.StillOutputTag
import com.vayunmathur.camera.platform.camera2.StillEngineInput
import com.vayunmathur.camera.platform.camera2.ReprocessRingGate
import com.vayunmathur.camera.platform.camera2.StillEnginePolicy
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera2 migration Phase 4a: still-capture glue between the gated
 * [Camera2Session] and the existing save pipeline.
 *
 * Reuse, not fork: a Camera2 still lands through exactly the helpers the
 * CameraX in-memory paths already use — [stampStillBytes] stamps the
 * orientation (from the mapped `CaptureResult`/sensor rotation, via the
 * same `rawStillOrientationString` the Motion Photo path uses) plus GPS
 * into the HAL JPEG, [saveStillBytes] routes it to SAF/MediaStore, and
 * when AVIF is the chosen format the bytes are decoded once and encoded
 * through `StillEncoder` via [saveProcessedStill] (which owns the
 * AVIF/JPEG choice and its JPEG fallback), with `writeCaptureExif`
 * restoring the EXIF the JPEG re-encode dropped.
 *
 * Everything [StillEnginePolicy] excludes (night, processed/bokeh,
 * Motion Photo, burst, capture-for-result, non-PHOTO modes) never reaches
 * this file: [tryCaptureCamera2Still] returns false and the CameraX
 * router runs exactly as before.
 */

/** Registers the live gated Camera2 session (null when it leaves composition). */
internal fun CameraViewModel.registerCamera2Session(session: Camera2Session?) {
    activeCamera2Session = session
}

private fun FlashMode.toFlashSetting(): FlashSetting = when (this) {
    FlashMode.ON -> FlashSetting.ON
    FlashMode.OFF -> FlashSetting.OFF
    FlashMode.AUTO -> FlashSetting.AUTO
}

/**
 * Attempts the shutter press on Camera2. Returns true when Camera2 took
 * the shot (the caller must not also route it to CameraX); false means
 * the shot is CameraX's, for a policy reason or because no gated session
 * is live. The caller ([capturePhoto]) has already claimed the in-flight
 * flag; every outcome here clears it.
 */
internal fun CameraViewModel.tryCaptureCamera2Still(): Boolean {
    val session = activeCamera2Session ?: return false
    val input = StillEngineInput(
        camera2Enabled = camera2PreviewEnabledMutable.value,
        mode = cameraModeMutable.value,
        hasJpegOutput = session.hasJpegOutput,
        nightRoute = nightPreviewActiveMutable.value || nightCaptureApplies(),
        processedRoute = warmthMutable.value != 0f || shadowsMutable.value != 0f,
        // Mirrors the (private) Motion Photo eligibility in CameraPhotoCapture:
        // a plain 4:3 PHOTO shot with no bake would route to Motion Photo.
        // An effective AVIF selection precludes that diversion (a Motion
        // Photo is a JPEG+MP4 trailer): the plain Camera2 still is taken
        // and encoded to .avif instead. The format is read here, at
        // capture time, so a settings change after bind is honoured.
        motionPhotoRoute = cameraModeMutable.value == CameraMode.PHOTO &&
            !captureForResult &&
            warmthMutable.value == 0f && shadowsMutable.value == 0f &&
            aspectRatioMutable.value == AspectRatioOption.RATIO_4_3 &&
            !StillFormatRouting.avifPrecludesMotionPhoto(
                requestedStillFormat(), StitchNative.isAvailable,
            ),
        captureForResult = captureForResult,
        burst = burstActiveMutable.value,
    )
    // Depth-phase ring inputs: gate + facade reprocess + live ring output.
    val ringInput = input.copy(
        ringEnabled = ReprocessRingGate.ENABLED && camera2PreviewEnabledMutable.value,
        reprocessSupported = session.reprocessSupported,
        hasRingOutput = session.hasRingOutput,
    )
    if (!StillEnginePolicy.usesCamera2(ringInput)) return false

    // Flash setting is the one control the preview effects do not push;
    // zoom/torch/EV/metering already live on the session.
    session.setFlashSetting(flashModeMutable.value.toFlashSetting())
    val mirrored = mirrorCaptures
    emitShutterFeedback()
    val stillCallback = object : Camera2Session.StillCallback {
            override fun onStillCaptured(still: CapturedStill) {
                viewModelScope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        runCatching { saveCamera2Still(still) }
                            .onFailure { Log.error("Camera2Still", "save failed", it) }
                            .getOrNull()
                    }
                    isCapturingMutable.value = false
                    if (uri != null) setLastCaptureUri(uri)
                }
            }

            override fun onStillFailed(message: String) {
                Log.status("Camera2Still", "capture failed: $message")
                isCapturingMutable.value = false
            }
        }
    // Ring first when the policy selects it; its runtime fallback to a
    // regular still lives inside captureFromRing. Night captures never
    // reach here (policy excludes the night route, Phase 6 is separate).
    // Ultra HDR intent mirrors CameraX exactly (see UltraHdrIntentPolicy):
    // capability-only, no separate user setting. The ring path reprocesses
    // to its JPEG output, so Ultra HDR applies to the direct still path;
    // an unsupported lens falls back to plain JPEG inside the session.
    val ultraHdrRequested = UltraHdrIntentPolicy.wantsUltraHdr(session.ultraHdrSupportedJpegR)
    if (StillEnginePolicy.usesRing(ringInput)) {
        session.captureFromRing(callback = stillCallback, mirrored = mirrored)
    } else {
        session.capture(
            mirrored = mirrored, callback = stillCallback,
            ultraHdrRequested = ultraHdrRequested,
        )
    }
    return true
}

/** The still format settings ask for right now (read at capture/save time, never cached at bind). */
internal fun CameraViewModel.requestedStillFormat(): StillFormat =
    if (photoFormatMutable.value == PhotoFormat.AVIF) StillFormat.AVIF else StillFormat.JPEG

/**
 * Saves one Camera2 still through the shared routing. Blocking: call off
 * main. Shared with the Phase-6 NIGHT extension glue so an extension JPEG
 * lands through exactly this Phase-4a path (stamp/AVIF/MediaStore).
 *
 * Every Camera2 still entry funnels here — direct capture, the ring's
 * reprocess and direct-encode fallbacks, and the night extension — so
 * this one decision covers them all: AVIF goes through the EXIF-carrying
 * `StillEncoder` via [saveProcessedStill]; an Ultra HDR source (the
 * JPEG_R output tag, or a gain map sniffed in the bytes) stays JPEG_R;
 * JPEG-selected shots keep the stamped-JPEG landing untouched.
 */
internal fun CameraViewModel.saveCamera2Still(still: CapturedStill): Uri? {
    val ultraHdrSource = still.tag == StillOutputTag.ULTRA_HDR_JPEG_R ||
        StillFormatRouting.isUltraHdrJpeg(still.jpegBytes)
    return when (StillFormatRouting.stillSaveRoute(requestedStillFormat(), ultraHdrSource)) {
        StillFormatRouting.StillSaveRoute.PROCESSED_ENCODER -> saveCamera2EncodedStill(still)
        StillFormatRouting.StillSaveRoute.JPEG_BYTES,
        StillFormatRouting.StillSaveRoute.ULTRA_HDR_JPEG_BYTES -> saveJpegStill(still)
    }
}

/** The AVIF landing for one Camera2 still: decode once, encode via [saveProcessedStill]. */
private fun CameraViewModel.saveCamera2EncodedStill(still: CapturedStill): Uri? {
    val rotation = still.metadata.rotationDegrees
    val bitmap = BitmapFactory.decodeByteArray(still.jpegBytes, 0, still.jpegBytes.size)
        ?: return saveJpegStill(still)
    try {
        val saved = saveProcessedStill(bitmap, rotation, still.jpegBytes) ?: return null
        if (saved.format == StillFormat.JPEG) {
            // The JPEG re-encode dropped the HAL EXIF; restore it from
            // the original frame, exactly as the processed CameraX path.
            writeCaptureExif(
                saved.uri, still.jpegBytes, rotation, mirrored = still.metadata.mirrored,
            )
        }
        return saved.uri
    } finally {
        bitmap.recycle()
    }
}

/** The plain JPEG landing: stamp orientation/GPS, then route to SAF/MediaStore. */
private fun CameraViewModel.saveJpegStill(still: CapturedStill): Uri? {
    val stamped = stampStillBytes(
        still.jpegBytes, still.metadata.rotationDegrees, still.metadata.mirrored,
    )
    return saveStillBytes("IMG_${MediaStoreSaver.timestamp()}.jpg", stamped)
}
