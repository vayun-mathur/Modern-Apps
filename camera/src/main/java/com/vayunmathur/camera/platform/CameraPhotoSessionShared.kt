package com.vayunmathur.camera.platform

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

import com.vayunmathur.library.log.Log
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import com.vayunmathur.camera.platform.lensSelector

/**
 * Shared photo-session probes shared by the photo/portrait bind paths: the AE
 * snapshot callback attach, the Ultra-HDR capability probe, and the default-res
 * fallback-failure recovery (UltraHDR+default → plain JPEG).
 */

/** Attaches the AE snapshot callback to a preview builder ([tag] names the session). */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.attachAeSnapshot(
    previewBuilder: Preview.Builder,
    tag: String
) {
    try {
        androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
            .setSessionCaptureCallback(aeSnapshotCallback)
        Log.dev("NightPreview", "$tag attached AE snapshot callback")
    } catch (e: IllegalStateException) {
        Log.error("NightPreview", "$tag Could not attach AE snapshot callback (was hidden as Warn)", e)
    } catch (e: IllegalArgumentException) {
        Log.error("NightPreview", "$tag Could not attach AE snapshot callback (was hidden as Warn)", e)
    }
}

/** Probes Ultra-HDR output support on the requested lens. */
internal fun CameraViewModel.probeUltraHdr(
    provider: ProcessCameraProvider,
    requestedLens: com.vayunmathur.camera.domain.PhysicalLens?
): Boolean {
    return try {
        val probeSelector = lensSelector(lensFacingMutable.value, requestedLens)
        val cameraInfo = provider.getCameraInfo(probeSelector)
        val caps = androidx.camera.core.ImageCapture.getImageCaptureCapabilities(cameraInfo)
            .supportedOutputFormats
            .contains(androidx.camera.core.ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
        Log.dev("NightPreview", "setupPhotoSession() ultraHdrSupported=$caps lens=${requestedLens?.labelKey}")
        caps
    } catch (e: IllegalStateException) {
        Log.error("NightPreview", "setupPhotoSession() Could not query Ultra HDR support (was hidden as Warn)", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error("NightPreview", "setupPhotoSession() Could not query Ultra HDR support (was hidden as Warn)", e)
        false
    }
}

/** Logs a default-res fallback failure and retries once as plain JPEG (or rethrows). */
internal suspend fun CameraViewModel.recoverFromFallbackFailure(
    provider: ProcessCameraProvider,
    e2: RuntimeException,
    ultraHdrSupported: Boolean,
    secondTry: suspend (Boolean) -> androidx.camera.core.Camera
): androidx.camera.core.Camera {
    Log.error(
        "NightPreview",
        "setupPhotoSession() default-res ultraHdr=$ultraHdrSupported bind FAILED (was hidden)",
        e2
    )
    if (!ultraHdrSupported) throw e2
    Log.error(
        "NightPreview",
        "setupPhotoSession() Ultra HDR bind failed (was Warn), " +
            "falling back to plain JPEG – lower quality path"
    )
    unbindQuietly(provider)
    return secondTry(false)
}
