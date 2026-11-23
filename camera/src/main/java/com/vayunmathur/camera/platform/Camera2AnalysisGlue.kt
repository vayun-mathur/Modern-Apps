package com.vayunmathur.camera.platform

import com.vayunmathur.camera.platform.camera2.AnalysisConsumer
import com.vayunmathur.camera.platform.camera2.AnalysisFrame

/**
 * Camera2 analysis wiring: the single plane consumer handed to every
 * Camera2 session's YUV analysis stream. It dispatches each frame to
 * the mode's current sink ([analysisSink]), which the screen effects
 * install per mode:
 * - PHOTO modes: [PhotoAnalyzer.onAnalysisFrame] (luma + QR + Motion
 *   Photo copies from planes/bitmaps),
 * - PORTRAIT: the bokeh segmenter over converted bitmaps,
 * - PANORAMA/PHOTOSPHERE: `PanoramaEngine.offerFrame` bitmaps,
 * - VIDEO modes: the Y-plane motion grid,
 * - SLOW_MO / NIGHT extension: no sink (null).
 *
 * One stable consumer for the session's whole life: the sink behind it
 * swaps with the mode, so a mode switch never reconfigures the session.
 */
internal fun CameraViewModel.camera2AnalysisConsumer(): AnalysisConsumer =
    AnalysisConsumer { frame -> dispatchAnalysisFrame(frame) }

/** Routes one Camera2 analysis frame to the installed mode sink. */
internal fun CameraViewModel.dispatchAnalysisFrame(frame: AnalysisFrame) {
    analysisSink?.invoke(frame)
}

/** Installs (or clears, with null) the per-mode analysis sink. */
internal fun CameraViewModel.setAnalysisSink(sink: ((AnalysisFrame) -> Unit)?) {
    analysisSink = sink
    analysisStreamActiveMutable.value = sink != null
}
