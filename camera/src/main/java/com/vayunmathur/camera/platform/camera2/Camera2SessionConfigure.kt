package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.view.Surface

/**
 * Session-configuration plumbing for [Camera2Session]: creating the
 * still/ring/analysis outputs for one open and creating the capture
 * session over them (with the analysis-combo fallback). Moved verbatim
 * from the session as extensions, following
 * `Camera2SessionStillDelivery.kt`; all state is touched only on the
 * session executor, as before.
 */

internal fun Camera2Session.configureSession(opened: DeviceHandle, gen: Long, reuseOutputs: Boolean = false) {
    val surface = previewSurface ?: return
    if (transition(SessionEvent.CONFIGURE_REQUESTED) !is TransitionResult.Applied) return
    if (reuseOutputs) {
        // Retry after a stream-combo failure dropped analysis: every
        // other output already exists; only the capture session is new.
        createCaptureSessionWithAnalysisFallback(opened, gen)
        return
    }
    // Outputs are chosen analytically from the guaranteed stream
    // combination table for this lens's hardware level (see
    // StreamSelection.planSessionStreams): a LIMITED lens gets
    // preview(<=1080p) + JPEG(MAX) + one YUV(<=1080p) and no JPEG_R/RAW/
    // second-YUV stream, so the first configure is legal by construction.
    // The profile ladder in Camera2PreviewSession remains only as a
    // safety net for HALs that reject even guaranteed combinations.
    val consumer = analysisConsumer
    val plan = StreamSelection.planSessionStreams(
        snapshot = snapshot,
        ringEnabled = ringConfig.enabled,
        rawEnabled = rawRingConfig.enabled,
        ultraHdrEnabled = ultraHdrEnabled,
        hasAnalysisConsumer = consumer != null && !analysisComboDropped,
    )
    // Second output: the JPEG still reader, when the lens reports JPEG
    // sizes and the driver can create it. Failure degrades to
    // preview-only (capture() then fails cleanly, policy excludes it).
    val jpegSize = plan.jpeg
    val output = jpegSize?.let { size ->
        runCatching { driver.createStillOutput(size) }.getOrNull()?.also { created ->
            created.setListener(JpegImageListener { bytes, width, height, _ ->
                onJpegAvailable(bytes, width, height)
            })
        }
    }
    stillOutput = output
    stillSize = if (output != null) jpegSize else null
    // Additional output: the JPEG_R (Ultra HDR) reader, only when the
    // facade advertises JPEG_R sizes. Failure degrades to no Ultra HDR
    // output (captures requesting it fall back to the JPEG output).
    val jpegRSize = plan.jpegR
    val createdUltraHdr = jpegRSize?.let { size ->
        runCatching { driver.createUltraHdrStillOutput(size) }.getOrNull()?.also { created ->
            created.setListener(JpegImageListener { bytes, width, height, _ ->
                onUltraHdrJpegAvailable(bytes, width, height)
            })
        }
    }
    ultraHdrOutput = createdUltraHdr
    ultraHdrSize = if (createdUltraHdr != null) jpegRSize else null
    // Third output: the YUV ring, only when this session was built
    // with the ring enabled. Failure degrades to no-ring (the
    // policy then excludes the ring path, capture stays Phase 4a).
    val selectedRingSize = plan.ring
    val createdRing = selectedRingSize?.let { size ->
        runCatching { driver.createRingOutput(size) }.getOrNull()?.also { created ->
            created.setListener(RingFrameListener { frame -> onRingFrame(frame) })
        }
    }
    ringOutput = createdRing
    ringSize = if (createdRing != null) selectedRingSize else null
    // Fourth output: the RAW ring, only when built with it enabled and
    // the facade proves RAW. RAW stream combos vary wildly per HAL, so
    // failure degrades to no RAW ring (policy excludes the RAW path).
    val selectedRawSize = plan.raw
    val selectedRawFormat = if (selectedRawSize != null) snapshot.rawRingFormat else null
    val createdRawRing = if (selectedRawSize != null && selectedRawFormat != null) {
        runCatching {
            driver.createRawRingOutput(selectedRawSize, selectedRawFormat)
        }.getOrNull()?.also { created ->
            created.setListener(RawRingFrameListener { frame -> onRawRingFrame(frame) })
        }
    } else {
        null
    }
    rawRingOutput = createdRawRing
    rawRingSize = if (createdRawRing != null) selectedRawSize else null
    rawRingFormat = if (createdRawRing != null) selectedRawFormat else null
    // Optional analysis output: only when a consumer was supplied.
    // Failure degrades to no analysis (preview/still unaffected).
    val selectedAnalysisSize = plan.analysis
    val createdAnalysis = if (selectedAnalysisSize != null && consumer != null) {
        runCatching {
            driver.createAnalysisOutput(
                selectedAnalysisSize,
                consumer,
                AnalysisFrameMetadata(
                    sensorOrientationDegrees = snapshot.sensorOrientationDegrees,
                    lensFacing = snapshot.lensFacing,
                    displayRotationDegrees = { displayRotationDegrees },
                ),
            )
        }.getOrNull()
    } else {
        null
    }
    analysisOutput = createdAnalysis
    analysisSize = if (createdAnalysis != null) selectedAnalysisSize else null
    com.vayunmathur.library.log.Log.status(
        "Camera2Session",
        "configure attempt gen=$gen reuse=$reuseOutputs hw=${snapshot.hardwareLevel} " +
            "preview=${previewSurface?.let { "set" }} jpeg=$jpegSize jpegR=$jpegRSize " +
            "ring=$selectedRingSize raw=$selectedRawSize/${selectedRawFormat} analysis=$selectedAnalysisSize " +
            "caps=${snapshot.availableCapabilities} maxJpeg=${StreamSelection.supportedJpegSizes(snapshot).maxByOrNull { it.area }} " +
            "maxYuv=${StreamSelection.supportedYuvSizes(snapshot).maxByOrNull { it.area }}",
    )
    createCaptureSessionWithAnalysisFallback(opened, gen)
}

/** Surfaces for one configure attempt: preview + every live output. */
internal fun Camera2Session.configuredSurfaces(): List<Surface> = listOfNotNull(
    previewSurface, stillOutput?.surface, ultraHdrOutput?.surface,
    ringOutput?.surface, rawRingOutput?.surface, analysisOutput?.surface,
)

/**
 * Creates the capture session over [configuredSurfaces]. A synchronous
 * create failure with analysis present drops analysis and retries once
 * without it; an asynchronous configure failure takes the same path in
 * [SessionCallbackImpl.onConfigureFailed]. Analysis never costs preview.
 */
internal fun Camera2Session.createCaptureSessionWithAnalysisFallback(opened: DeviceHandle, gen: Long) {
    val callback = SessionCallbackImpl(gen)
    val ringSize = ringSize
    runCatching {
        if (ringOutput != null && ringSize != null && snapshot.supportsYuvRingReprocess) {
            opened.createReprocessableCaptureSession(
                ReprocessInputConfig(ringSize.width, ringSize.height, ImageFormat.YUV_420_888),
                configuredSurfaces(),
                callback,
            )
        } else {
            opened.createCaptureSession(configuredSurfaces(), callback)
        }
    }.onFailure {
        if (analysisOutput != null && !analysisComboDropped) {
            dropAnalysisOutput()
            createCaptureSessionWithAnalysisFallback(opened, gen)
        } else {
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("createCaptureSession failed: ${it.message}")
        }
    }
}

/** Drops the analysis output (combo degrade); preview/still keep serving. */
internal fun Camera2Session.dropAnalysisOutput() {
    analysisComboDropped = true
    runCatching { analysisOutput?.close() }
    analysisOutput = null
    analysisSize = null
}
