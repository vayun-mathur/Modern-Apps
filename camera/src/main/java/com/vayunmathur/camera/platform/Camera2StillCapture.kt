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
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CapturedStill
import com.vayunmathur.camera.platform.camera2.StillEngineInput
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
        motionPhotoRoute = cameraModeMutable.value == CameraMode.PHOTO &&
            !captureForResult &&
            warmthMutable.value == 0f && shadowsMutable.value == 0f &&
            aspectRatioMutable.value == AspectRatioOption.RATIO_4_3,
        captureForResult = captureForResult,
        burst = burstActiveMutable.value,
    )
    if (!StillEnginePolicy.usesCamera2(input)) return false

    // Flash setting is the one control the preview effects do not push;
    // zoom/torch/EV/metering already live on the session.
    session.setFlashSetting(flashModeMutable.value.toFlashSetting())
    val mirrored = mirrorCaptures
    emitShutterFeedback()
    session.capture(
        mirrored = mirrored,
        callback = object : Camera2Session.StillCallback {
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
        },
    )
    return true
}

/**
 * Saves one Camera2 still through the shared routing. Blocking: call off
 * main. Shared with the Phase-6 NIGHT extension glue so an extension JPEG
 * lands through exactly this Phase-4a path (stamp/AVIF/MediaStore).
 */
internal fun CameraViewModel.saveCamera2Still(still: CapturedStill): Uri? {
    val rotation = still.metadata.rotationDegrees
    if (photoFormatMutable.value == PhotoFormat.AVIF) {
        val bitmap = BitmapFactory.decodeByteArray(still.jpegBytes, 0, still.jpegBytes.size)
            ?: return saveJpegStill(still)
        try {
            val saved = saveProcessedStill(bitmap, rotation) ?: return null
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
    return saveJpegStill(still)
}

/** The plain JPEG landing: stamp orientation/GPS, then route to SAF/MediaStore. */
private fun CameraViewModel.saveJpegStill(still: CapturedStill): Uri? {
    val stamped = stampStillBytes(
        still.jpegBytes, still.metadata.rotationDegrees, still.metadata.mirrored,
    )
    return saveStillBytes("IMG_${MediaStoreSaver.timestamp()}.jpg", stamped)
}
