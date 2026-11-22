package com.vayunmathur.camera.platform

import com.vayunmathur.camera.domain.AnalysisSource
import com.vayunmathur.camera.domain.AnalysisSourceInput
import com.vayunmathur.camera.domain.AnalysisSourcePolicy
import com.vayunmathur.camera.platform.camera2.AnalysisConsumer

/**
 * Camera2 analysis wiring: the plane consumer for a Camera2 session's YUV
 * analysis stream.
 *
 * The consumer is luma-only by construction: it feeds each frame's Y plane
 * to [PhotoAnalyzer.onYPlane] (luma stats via the Rust plane-stats work in
 * `StitchNative.lumaStats`, Kotlin fallback identical), which drives the
 * night hysteresis through [onLuminance]. QR decode and bitmap frame
 * copies deliberately have no plane entry point (see
 * [AnalysisSourcePolicy]): sessions needing them — QR, PORTRAIT,
 * PANORAMA/PHOTOSPHERE, Motion-Photo frame copies — stay on the CameraX
 * ImageAnalysis path, exactly as before. Those session types never compose
 * the Camera2 preview, so they never reach this glue.
 */
internal fun CameraViewModel.camera2AnalysisConsumer(): AnalysisConsumer {
    val analyzer = PhotoAnalyzer(
        onLuminance = { onLuminance(it) },
        onQrDetected = {},
    )
    return AnalysisConsumer { frame -> analyzer.onYPlane(frame.yPlane) }
}

/**
 * True when this session's analysis stream may serve from Camera2: the
 * persisted Camera2 preview toggle (the session only exists when that
 * gate selected Camera2) AND the [AnalysisSourcePolicy] master flag, for a
 * cheap-plane-only consumer. Excluded (QR/bitmap) sessions are not
 * classified here — they never compose a Camera2 session.
 */
internal fun CameraViewModel.camera2AnalysisServes(): Boolean =
    AnalysisSourcePolicy.select(
        AnalysisSourceInput(
            camera2Enabled = AnalysisSourcePolicy.CAMERA2_ENABLED &&
                camera2PreviewEnabledMutable.value,
            needsQr = false,
            needsBitmapFrame = false,
            cheapPlaneConsumersOnly = true,
        ),
    ) == AnalysisSource.CAMERA2_IMAGE_READER
