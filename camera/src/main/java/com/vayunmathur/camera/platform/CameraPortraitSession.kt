package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import android.util.Size
import com.vayunmathur.camera.platform.bindWithFallback
import com.vayunmathur.camera.platform.currentLensFamily
import com.vayunmathur.camera.platform.ensureLensesEnumerated
import com.vayunmathur.camera.platform.lensSelector
import com.vayunmathur.camera.platform.refreshCapabilities
import com.vayunmathur.camera.data.setFlashMode

/**
 * Portrait session: full-resolution ImageCapture (final image stays max-res) but capped
 * ImageAnalysis (~0.8 MP, 1024x768) for smooth preview segmentation. This fixes the
 * "No supported surface combination" bind failures that happened when portrait reused
 * the then-uncapped 3-stream photo path (Preview + max ImageCapture + max ImageAnalysis) for a
 * model that only needs 256x256.
 *
 * Fallback ladder prioritizes keeping max-res capture:
 * capped+max+UHD → capped+max+JPEG → capped+default+UHD → capped+default+JPEG → default+default
 */
suspend fun CameraViewModel.setupPortraitSession(): Boolean {
    Log.dev(
        "NightPreview",
        "setupPortraitSession() ENTRY lens=${lensFacingMutable.value} thread=${Thread.currentThread().name}"
    )
    return try {
        val session = preparePortraitSession()
        bindPortraitLadder(session)
        finishPortraitSession()
        true
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupPortraitSession() OUTER CATCH – Failed black root? " +
                "${e.javaClass.simpleName} ${e.message}",
            e
        )
        false
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupPortraitSession() OUTER CATCH – Failed black root? " +
                "${e.javaClass.simpleName} ${e.message}",
            e
        )
        false
    }
}

/** Portrait session scaffolding: provider, lens, preview, owner, Ultra-HDR probe. */
internal data class PortraitSessionPrep(
    val provider: ProcessCameraProvider,
    val portraitLens: com.vayunmathur.camera.domain.PhysicalLens?,
    val portraitFamily: List<com.vayunmathur.camera.domain.PhysicalLens>,
    val preview: Preview,
    val owner: ManualLifecycleOwner,
    val ultraHdrSupported: Boolean
)

/** Binds provider/owner/preview and probes Ultra HDR for the portrait session. */
internal suspend fun CameraViewModel.preparePortraitSession(): PortraitSessionPrep {
    val provider = ProcessCameraProvider.awaitInstance(app)
    cameraProvider = provider
    Log.dev("NightPreview", "setupPortraitSession() providerHash=${provider.hashCode()}")
    provider.unbindAll()

    ensureLensesEnumerated(provider)
    val portraitLens = selectedLensMutable.value
    val portraitFamily = currentLensFamily()

    val previewBuilder = Preview.Builder()
    attachAeSnapshot(previewBuilder, "setupPortraitSession()")
    val preview = previewBuilder.build()
    preview.setSurfaceProvider { request ->
        Log.dev(
            "NightPreview",
            "setupPortraitSession() surfaceRequest res=${request.resolution} " +
                "thread=${Thread.currentThread().name}"
        )
        surfaceRequestMutable.value = request
    }

    val owner = ManualLifecycleOwner()
    owner.start()
    sessionLifecycleOwner = owner

    val ultraHdrSupported = probePortraitUltraHdr(provider, portraitLens)
    return PortraitSessionPrep(provider, portraitLens, portraitFamily, preview, owner, ultraHdrSupported)
}

/** Probes Ultra-HDR output support on the portrait lens. */
internal fun CameraViewModel.probePortraitUltraHdr(
    provider: ProcessCameraProvider,
    portraitLens: com.vayunmathur.camera.domain.PhysicalLens?
): Boolean {
    return try {
        val cameraInfo = provider.getCameraInfo(lensSelector(lensFacingMutable.value, portraitLens))
        val sup = ImageCapture.getImageCaptureCapabilities(cameraInfo)
            .supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
        Log.dev("NightPreview", "setupPortraitSession() ultraHdrSupported=$sup lens=${portraitLens?.labelKey}")
        sup
    } catch (e: IllegalStateException) {
        Log.error("NightPreview", "setupPortraitSession() Could not query Ultra HDR support (hidden)", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error("NightPreview", "setupPortraitSession() Could not query Ultra HDR support (hidden)", e)
        false
    }
}

/** Build attempt ladder; keep max-res capture attempts first per user request. */
internal data class PortraitAttempt(val capped: Boolean, val maxRes: Boolean, val ultra: Boolean)

/** Portrait attempt rungs for Ultra-HDR-capable vs plain pipelines. */
internal fun portraitAttempts(ultraHdrSupported: Boolean): List<PortraitAttempt> {
    if (ultraHdrSupported) {
        return listOf(
            PortraitAttempt(true, true, true),
            PortraitAttempt(true, true, false),
            PortraitAttempt(true, false, true),
            PortraitAttempt(true, false, false),
            PortraitAttempt(false, false, true),
            PortraitAttempt(false, false, false)
        )
    }
    return listOf(
        PortraitAttempt(true, true, false),
        PortraitAttempt(true, false, false),
        PortraitAttempt(false, false, false)
    )
}

/**
 * Runs the portrait bind ladder (each resolution rung across the lens ladder),
 * keeping max-res capture. Records the bound lens id.
 */
internal suspend fun CameraViewModel.bindPortraitLadder(session: PortraitSessionPrep) {
    var bound: Camera? = null
    var portraitBoundLensId: String? = null
    var lastError: Exception? = null
    for ((capped, maxRes, ultra) in portraitAttempts(session.ultraHdrSupported)) {
        val rung = tryPortraitRungSet(session, capped, maxRes, ultra)
        lastError = rung.lastError ?: lastError
        if (rung.bound != null) {
            portraitBoundLensId = rung.lensId
            bound = rung.bound
            break
        }
    }
    if (bound == null) {
        Log.error(
            "NightPreview",
            "setupPortraitSession() ALL attempts FAILED! " +
                "lastError=${lastError?.javaClass?.simpleName} ${lastError?.message} – " +
                "produces black preview?",
            lastError ?: IllegalStateException("none")
        )
    }
    boundCamera = bound ?: throw (lastError ?: IllegalStateException("Portrait session bind failed"))
    boundLensIdField = portraitBoundLensId
}

/** Outcome of one portrait resolution rung across the lens ladder. */
internal data class PortraitRungResult(
    val bound: Camera?,
    val lensId: String?,
    val lastError: Exception?
)

/** Tries one resolution rung across the lens ladder (requested → wide → any). */
internal fun CameraViewModel.tryPortraitRungSet(
    session: PortraitSessionPrep,
    capped: Boolean,
    maxRes: Boolean,
    ultra: Boolean
): PortraitRungResult {
    // Each resolution rung is tried across the lens ladder (requested → wide → any).
    val lensOrdered = buildList {
        if (session.portraitLens != null) add(session.portraitLens)
        session.portraitFamily.sortedBy { it.fallbackPriority }
            .forEach { if (it != session.portraitLens) add(it) }
        if (session.portraitLens == null && session.portraitFamily.isEmpty()) add(null)
    }
    var lastError: Exception? = null
    for (candidate in lensOrdered) {
        if (tryPortraitRung(session, candidate, capped, maxRes, ultra)) {
            return PortraitRungResult(boundCamera, candidate?.logicalCameraId, lastError)
        } else {
            lastError = portraitLastError
        }
    }
    return PortraitRungResult(null, null, lastError)
}

/** Last portrait bind error, published by [tryPortraitRung] for the ladder report. */
internal var portraitLastError: Exception? = null

/** Tries one portrait rung on one lens; true when it binds. */
internal fun CameraViewModel.tryPortraitRung(
    session: PortraitSessionPrep,
    candidate: com.vayunmathur.camera.domain.PhysicalLens?,
    capped: Boolean,
    maxRes: Boolean,
    ultra: Boolean
): Boolean {
    return try {
        session.provider.unbindAll()
        boundCamera = bindPortraitUseCases(session, candidate, capped, maxRes, ultra)
        if (candidate != session.portraitLens) {
            Log.status("LensSelector", "Portrait fell back to lens=${candidate?.labelKey}")
        }
        Log.dev(
            "NightPreview",
            "setupPortraitSession() bind ladder SUCCESS capped=$capped maxRes=$maxRes " +
                "ultra=$ultra lens=${candidate?.labelKey} " +
                "zoom min=${boundCamera?.cameraInfo?.zoomState?.value?.minZoomRatio} " +
                "max=${boundCamera?.cameraInfo?.zoomState?.value?.maxZoomRatio}"
        )
        true
    } catch (e: IllegalStateException) {
        portraitLastError = e
        Log.error(
            "NightPreview",
            "setupPortraitSession() Portrait bind failed " +
                "(capped=$capped maxRes=$maxRes ultra=$ultra lens=${candidate?.labelKey}) – " +
                "was Warn with swallowed stack, root of black? " +
                "${e.javaClass.simpleName} msg=${e.message}",
            e
        )
        unbindQuietly(session.provider)
        false
    } catch (e: IllegalArgumentException) {
        portraitLastError = e
        Log.error(
            "NightPreview",
            "setupPortraitSession() Portrait bind failed " +
                "(capped=$capped maxRes=$maxRes ultra=$ultra lens=${candidate?.labelKey}) – " +
                "was Warn with swallowed stack, root of black? " +
                "${e.javaClass.simpleName} msg=${e.message}",
            e
        )
        unbindQuietly(session.provider)
        false
    }
}

/** Binds Preview + ImageCapture + capped/default ImageAnalysis for portrait. */
internal fun CameraViewModel.bindPortraitUseCases(
    session: PortraitSessionPrep,
    candidate: com.vayunmathur.camera.domain.PhysicalLens?,
    cappedAnalysis: Boolean,
    maxResCapture: Boolean,
    ultraHdr: Boolean
): Camera {
    Log.dev(
        "NightPreview",
        "setupPortraitSession() bind(capped=$cappedAnalysis maxRes=$maxResCapture ultra=$ultraHdr) START"
    )
    return try {
        val capture = buildPortraitCapture(maxResCapture, ultraHdr)
        val analysis = buildPortraitAnalysis(cappedAnalysis, maxResCapture)
        bindSession(
            session.provider,
            session.owner,
            lensSelector(lensFacingMutable.value, candidate),
            session.preview,
            capture,
            analysis
        ).also {
            Log.dev(
                "NightPreview",
                "setupPortraitSession() bind SUCCESS capped=$cappedAnalysis " +
                    "maxRes=$maxResCapture ultra=$ultraHdr " +
                    "zoom min=${it.cameraInfo.zoomState.value?.minZoomRatio} " +
                    "max=${it.cameraInfo.zoomState.value?.maxZoomRatio}"
            )
        }
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupPortraitSession() bind(capped=$cappedAnalysis maxRes=$maxResCapture " +
                "ultra=$ultraHdr) FAILED – swallowed before! " +
                "${e.javaClass.simpleName} ${e.message}",
            e
        )
        throw e
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupPortraitSession() bind(capped=$cappedAnalysis maxRes=$maxResCapture " +
                "ultra=$ultraHdr) FAILED – swallowed before! " +
                "${e.javaClass.simpleName} ${e.message}",
            e
        )
        throw e
    }
}

/** Builds the max/default-res portrait ImageCapture with the still crop applied. */
internal fun CameraViewModel.buildPortraitCapture(maxResCapture: Boolean, ultraHdr: Boolean): ImageCapture {
    // Capture: always try max-res first to keep final image full-res.
    val captureSelectorBuilder = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
    if (maxResCapture) {
        captureSelectorBuilder.setAllowedResolutionMode(
            ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
        )
    }
    val captureBuilder = ImageCapture.Builder()
        .setResolutionSelector(captureSelectorBuilder.build())
        .setFlashMode(getImageCaptureFlashMode())
    if (ultraHdr) {
        captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
    }
    val capture = captureBuilder.build()
    imageCapture = capture
    // Crop stills to the selected aspect ratio (1:1 / 3:2 / 16:9 / 4:3). CameraX crops
    // OutputFileOptions saves to this and exposes it as cropRect for in-memory shots.
    capture.setCropAspectRatio(currentCropAspectRatio())
    return capture
}

/** Builds the capped/default portrait analysis stream for [cappedAnalysis]/[maxResCapture]. */
internal fun CameraViewModel.buildPortraitAnalysis(cappedAnalysis: Boolean, maxResCapture: Boolean): ImageAnalysis {
    val analysisBuilder = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    if (cappedAnalysis) {
        analysisBuilder.setResolutionSelector(
            ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(
                    Size(PORTRAIT_ANALYSIS_WIDTH, PORTRAIT_ANALYSIS_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            ).build()
        )
    } else if (maxResCapture) {
        analysisBuilder.setResolutionSelector(
            ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
            ).build()
        )
    }
    val analysis = analysisBuilder.build()
    imageAnalysis = analysis
    return analysis
}

/** Refreshes capabilities and marks the portrait session active. */
internal suspend fun CameraViewModel.finishPortraitSession() {
    val zsPor = boundCamera?.cameraInfo?.zoomState?.value
    Log.dev(
        "NightPreview",
        "setupPortraitSession() final zoom min=${zsPor?.minZoomRatio} max=${zsPor?.maxZoomRatio} " +
            "ratio=${zsPor?.zoomRatio} – if max=1, zoom bar will show only 1x"
    )
    applyManualControls()
    boundCamera?.let { refreshCapabilities(it, boundLensIdField) }
    onSessionBound()
    photoSessionActiveMutable.value = true
    Log.dev(
        "NightPreview",
        "setupPortraitSession() SUCCESS photoActive=true " +
            "surface=${surfaceRequestMutable.value?.resolution}"
    )
}
