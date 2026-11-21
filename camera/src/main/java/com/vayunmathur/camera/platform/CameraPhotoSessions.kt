package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import com.vayunmathur.camera.platform.bindWithFallback
import com.vayunmathur.camera.platform.currentLensFamily
import com.vayunmathur.camera.platform.ensureLensesEnumerated
import com.vayunmathur.camera.platform.lensSelector
import com.vayunmathur.camera.platform.refreshCapabilities
import com.vayunmathur.camera.data.setFlashMode

/** Capped analysis-stream resolutions (w x h) for photo/pano/portrait sessions. */
internal const val PHOTO_ANALYSIS_WIDTH = 1280
internal const val PHOTO_ANALYSIS_HEIGHT = 960
internal const val PANO_ANALYSIS_WIDTH = 2016
internal const val PANO_ANALYSIS_HEIGHT = 1512
internal const val PORTRAIT_ANALYSIS_WIDTH = 1024
internal const val PORTRAIT_ANALYSIS_HEIGHT = 768

/** Bound lens id carried from ladders to finish steps. */
internal var boundLensIdField: String? = null

suspend fun CameraViewModel.setupNightPreviewSession(): Boolean {
    Log.dev(
        "NightPreview",
        "setupNightPreviewSession() ENTRY thread=${Thread.currentThread().name} " +
            "lens=${lensFacingMutable.value} surfaceBefore=${surfaceRequestMutable.value?.resolution}"
    )
    return try {
        val session = prepareNightPreview() ?: return setupPhotoSession()
        bindNightPreview(session)
        finishNightPreview(session)
        true
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupNightPreviewSession() OUTER CATCH – FAILED to set up night preview, " +
                "falling back to normal photo session. " +
                "Root cause of solid black: exception=${e.javaClass.simpleName} msg=${e.message}",
            e
        )
        fallbackToPhotoSession()
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupNightPreviewSession() OUTER CATCH – FAILED to set up night preview, " +
                "falling back to normal photo session. " +
                "Root cause of solid black: exception=${e.javaClass.simpleName} msg=${e.message}",
            e
        )
        fallbackToPhotoSession()
    }
}

/** Night-preview scaffolding: provider, manager, lens, analysis support, use cases. */
internal data class NightPreviewPrep(
    val provider: ProcessCameraProvider,
    val mgr: ExtensionsManager,
    val requestedNightLens: com.vayunmathur.camera.domain.PhysicalLens?,
    val baseSelector: CameraSelector,
    val analysisSupported: Boolean,
    val preview: Preview,
    val owner: ManualLifecycleOwner,
    val capture: ImageCapture
)

/**
 * Resolves provider/manager/lens and probes extension + analysis support.
 * Null when the manager is missing (caller falls back to the normal photo session).
 */
@Suppress("DEPRECATION")
internal suspend fun CameraViewModel.prepareNightPreview(): NightPreviewPrep? {
    val provider = ProcessCameraProvider.awaitInstance(app)
    cameraProvider = provider
    Log.dev("NightPreview", "setupNightPreviewSession() got cameraProvider=$provider")
    val mgr = getExtensionsManager(provider)
    Log.dev(
        "NightPreview",
        "setupNightPreviewSession() ExtensionsManager=${mgr != null} cacheExt=${extensionsManager != null}"
    )
    if (mgr == null) {
        Log.status("NightPreview", "setupNightPreviewSession() manager NULL, falling back to normal photo session")
        return null
    }
    // Night extension is wide-only on most vendors; pin to the selected lens so the
    // availability probe reflects it, and fall back to normal photo when it can't bind.
    ensureLensesEnumerated(provider)
    val requestedNightLens = selectedLensMutable.value
    val baseSelector = lensSelector(lensFacingMutable.value, requestedNightLens)
    if (!isNightExtensionSupported(mgr, baseSelector)) return null
    Log.dev("NightPreview", "setupNightPreviewSession() unbinding all before night selector")
    try {
        provider.unbindAll()
        Log.dev("NightPreview", "setupNightPreviewSession() provider.unbindAll SUCCESS")
    } catch (e: IllegalStateException) {
        Log.error("NightPreview", "setupNightPreviewSession() unbindAll FAILED (was hidden)", e)
        throw e
    } catch (e: IllegalArgumentException) {
        Log.error("NightPreview", "setupNightPreviewSession() unbindAll FAILED (was hidden)", e)
        throw e
    }
    val analysisSupported = isNightAnalysisSupported(mgr, baseSelector)

    // Proven-on-stock path: getExtensionEnabledCameraSelector + bindToLifecycle. It's
    // deprecated in 1.7.0-alpha02, but it's what actually binds on devices where the vendor
    // NIGHT extender works. On GrapheneOS/Pixel it throws "Framework size list map ...", but
    // we never get here there: isNightExtensionAvailable() probes first and hides the mode.
    val nightSelector = mgr.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)

    val preview = Preview.Builder().build()
    preview.setSurfaceProvider { request ->
        Log.dev("NightPreview", "setupNightPreviewSession() NEW surfaceRequest emitted res=${request.resolution}")
        surfaceRequestMutable.value = request
    }

    val owner = ManualLifecycleOwner()
    owner.start()
    sessionLifecycleOwner = owner

    val capture = ImageCapture.Builder()
        .setFlashMode(getImageCaptureFlashMode())
        .build()
    imageCapture = capture
    capture.setScreenFlash(getScreenFlashForCapture())
    return NightPreviewPrep(
        provider,
        mgr,
        requestedNightLens,
        nightSelector,
        analysisSupported,
        preview,
        owner,
        capture
    )
}

/** Probes NIGHT extension availability; false (fall back) when unavailable or unreadable. */
internal fun CameraViewModel.isNightExtensionSupported(
    mgr: ExtensionsManager,
    baseSelector: CameraSelector
): Boolean {
    val extAvail = try {
        mgr.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT)
    } catch (e: IllegalStateException) {
        Log.error("NightPreview", "setupNightPreviewSession() isExtensionAvailable threw (was hidden)", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error("NightPreview", "setupNightPreviewSession() isExtensionAvailable threw (was hidden)", e)
        false
    }
    Log.dev(
        "NightPreview",
        "setupNightPreviewSession() isExtensionAvailable(NIGHT)=$extAvail lens=${lensFacingMutable.value}"
    )
    if (!extAvail) {
        Log.status(
            "NightPreview",
            "setupNightPreviewSession() extension NOT available on lens=${lensFacingMutable.value}, " +
                "falling back"
        )
        return false
    }
    return true
}

/** Probes concurrent-analysis support inside the NIGHT extension session. */
internal fun CameraViewModel.isNightAnalysisSupported(
    mgr: ExtensionsManager,
    baseSelector: CameraSelector
): Boolean {
    val analysisSupported = try {
        mgr.isImageAnalysisSupported(baseSelector, ExtensionMode.NIGHT)
    } catch (e: IllegalStateException) {
        Log.error("NightPreview", "setupNightPreviewSession() isImageAnalysisSupported query FAILED", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error("NightPreview", "setupNightPreviewSession() isImageAnalysisSupported query FAILED", e)
        false
    }
    Log.dev("NightPreview", "setupNightPreviewSession() isImageAnalysisSupported=$analysisSupported")
    return analysisSupported
}

/** Binds the NIGHT extension session, retrying without analysis when supported. */
internal fun CameraViewModel.bindNightPreview(session: NightPreviewPrep) {
    boundCamera = try {
        bindNightUseCases(session, withAnalysis = session.analysisSupported)
    } catch (e: IllegalStateException) {
        Log.status(
            "NightPreview",
            "setupNightPreviewSession() bind FAILED " +
                "withAnalysis=${session.analysisSupported}: ${e.javaClass.simpleName} ${e.message}",
            e
        )
        if (!session.analysisSupported) throw e
        try { session.provider.unbindAll() } catch (_: Exception) {}
        bindNightUseCases(session, withAnalysis = false)
    } catch (e: IllegalArgumentException) {
        Log.status(
            "NightPreview",
            "setupNightPreviewSession() bind FAILED " +
                "withAnalysis=${session.analysisSupported}: ${e.javaClass.simpleName} ${e.message}",
            e
        )
        if (!session.analysisSupported) throw e
        try { session.provider.unbindAll() } catch (_: Exception) {}
        bindNightUseCases(session, withAnalysis = false)
    }
}

/** Binds the night extension use cases, with or without the analysis stream. */
internal fun CameraViewModel.bindNightUseCases(session: NightPreviewPrep, withAnalysis: Boolean): Camera {
    Log.dev("NightPreview", "setupNightPreviewSession() bind(withAnalysis=$withAnalysis) START")
    return if (withAnalysis) {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        imageAnalysis = analysis
        bindSession(session.provider, session.owner, session.baseSelector, session.preview, session.capture, analysis)
    } else {
        Log.status(
            "NightPreview",
            "setupNightPreviewSession() binding NIGHT without ImageAnalysis – QR scanning, " +
                "luminance sampling and Motion Photo are off for this session"
        )
        imageAnalysis = null
        bindSession(session.provider, session.owner, session.baseSelector, session.preview, session.capture)
    }
}

/** Marks the night preview active and wires extension observers. */
internal suspend fun CameraViewModel.finishNightPreview(session: NightPreviewPrep) {
    val zs = boundCamera?.cameraInfo?.zoomState?.value
    Log.dev(
        "NightPreview",
        "setupNightPreviewSession() boundCamera zoomState min=${zs?.minZoomRatio} " +
            "max=${zs?.maxZoomRatio} current=${zs?.zoomRatio} – vendor NIGHT extension often reports " +
            "1x-only; this explains zoom bar disappearing (only 1x). Full-res capture is still max-res? " +
            "No, extension uses default resolution, lower than max-res photo session, " +
            "cannot take full quality while in extension preview"
    )
    // Do NOT observe getNightModeIndicator() on the extension camera: it reports
    // UNKNOWN/NOT_RECOMMENDED there, which fights the normal session's RECOMMENDED reading
    // and flips lowLightDetectedMutable → an engage/disengage toggle loop. Night stays engaged
    // (frozen at the value the normal session detected) until the moon button turns it off.
    // We do watch the extension camera's state to catch async ExtensionCaptureSession
    // failures, and its strength for the UI indicator.
    boundCamera?.cameraInfo?.let {
        observeExtensionStrength(it)
        observeExtensionCameraState(it)
    }
    boundCamera?.let { refreshCapabilities(it, session.requestedNightLens?.logicalCameraId) }
    onSessionBound()
    nightPreviewActiveMutable.value = true
    photoSessionActiveMutable.value = true
    Log.dev(
        "NightPreview",
        "setupNightPreviewSession() SUCCESS – nightPreviewActive=true photoSessionActive=true " +
            "surfaceRequest=${surfaceRequestMutable.value?.resolution}"
    )
}

/** Records the extension failure and falls back to the normal photo session. */
internal suspend fun CameraViewModel.fallbackToPhotoSession(): Boolean {
    nightPreviewActiveMutable.value = false
    // The extension genuinely can't bind here (e.g. GrapheneOS/Pixel). Remember it so night
    // isn't offered again for a week; flipping nightExtensionUsable false also makes the UI
    // drop useNightPreview immediately, so we don't loop back into this failure.
    recordNightExtensionFailure()
    val fallback = try {
        setupPhotoSession()
    } catch (e2: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupNightPreviewSession() fallback setupPhotoSession() ALSO FAILED (double hidden)",
            e2
        )
        false
    } catch (e2: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupNightPreviewSession() fallback setupPhotoSession() ALSO FAILED (double hidden)",
            e2
        )
        false
    }
    Log.dev("NightPreview", "setupNightPreviewSession() fallback result=$fallback")
    return fallback
}

/**
 * Binds a lean Preview + capped-resolution ImageAnalysis session for the
 * panorama and photo-sphere modes. These sweep off the analysis stream and
 * never use ImageCapture. The analysis stream is capped at ~3 MP: the pano
 * analyzer decodes every delivered frame to a Bitmap, so an uncapped max-res
 * stream janks the preview, while the 8 MP compose canvas means higher
 * per-frame resolution barely affects the stitched output.
 */
suspend fun CameraViewModel.setupPanoramaSession(): Boolean {
    return try {
        val session = preparePanoramaSession()
        bindPanoLadder(session)
        finishPanoramaSession()
        true
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupPanoramaSession() OUTER CATCH – Failed solid black? " +
                "${e.javaClass.simpleName} ${e.message}",
            e
        )
        false
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupPanoramaSession() OUTER CATCH – Failed solid black? " +
                "${e.javaClass.simpleName} ${e.message}",
            e
        )
        false
    }
}

/** Panorama session scaffolding: provider, lens, preview, owner. */
internal data class PanoSessionPrep(
    val provider: ProcessCameraProvider,
    val panoLens: com.vayunmathur.camera.domain.PhysicalLens?,
    val panoFamily: List<com.vayunmathur.camera.domain.PhysicalLens>,
    val preview: Preview,
    val owner: ManualLifecycleOwner
)

/** Binds provider/owner/preview for the panorama sweep session. */
internal suspend fun CameraViewModel.preparePanoramaSession(): PanoSessionPrep {
    val provider = ProcessCameraProvider.awaitInstance(app)
    cameraProvider = provider
    provider.unbindAll()

    ensureLensesEnumerated(provider)
    val panoLens = selectedLensMutable.value
    val panoFamily = currentLensFamily()

    val previewBuilder = Preview.Builder()
    val preview = previewBuilder.build()
    preview.setSurfaceProvider { request -> surfaceRequestMutable.value = request }

    val owner = ManualLifecycleOwner()
    owner.start()
    sessionLifecycleOwner = owner
    return PanoSessionPrep(provider, panoLens, panoFamily, preview, owner)
}

/**
 * Binds the capped-then-default analysis ladder for the panorama sweep.
 *
 * Cap the analysis stream at ~3 MP. The compose canvas is bounded to
 * 8 MP, so per-frame resolution beyond a few MP adds little to the
 * stitched output — but the analyzer converts every delivered frame to
 * a Bitmap, so a max-res stream makes that conversion (and its GC
 * churn) heavy enough to jank the preview during the sweep. ~3 MP keeps
 * it smooth. Falls back to the device-default analysis resolution if
 * this bound can't bind.
 */
internal fun CameraViewModel.bindPanoLadder(session: PanoSessionPrep) {
    var panoBoundLensId: String? = null
    boundCamera = try {
        Log.dev("NightPreview", "setupPanoramaSession() bind capped=true START")
        val (lens, camera) = bindWithFallback(session.provider, session.panoLens, session.panoFamily) {
            lensSel ->
            bindPanoUseCases(session, lensSel, capped = true)
        }
        panoBoundLensId = lens?.logicalCameraId
        camera
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupPanoramaSession() Capped panorama bind FAILED (was hidden as Warn), " +
                "retrying at default – resolution lower!",
            e
        )
        unbindQuietly(session.provider)
        bindPanoDefault(session) { lens ->
            panoBoundLensId = lens?.logicalCameraId
        }
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupPanoramaSession() Capped panorama bind FAILED (was hidden as Warn), " +
                "retrying at default – resolution lower!",
            e
        )
        unbindQuietly(session.provider)
        bindPanoDefault(session) { lens ->
            panoBoundLensId = lens?.logicalCameraId
        }
    }
    boundLensIdField = panoBoundLensId
}

/** Default-resolution leg of the panorama bind ladder. */
internal fun CameraViewModel.bindPanoDefault(
    session: PanoSessionPrep,
    onLens: (com.vayunmathur.camera.domain.PhysicalLens?) -> Unit
): Camera {
    return try {
        val (lens, camera) = bindWithFallback(session.provider, session.panoLens, session.panoFamily) {
            lensSel ->
            bindPanoUseCases(session, lensSel, capped = false)
        }
        onLens(lens)
        camera
    } catch (e2: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupPanoramaSession() default bind ALSO FAILED – " +
                "black root ${e2.javaClass.simpleName} ${e2.message}",
            e2
        )
        throw e2
    } catch (e2: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupPanoramaSession() default bind ALSO FAILED – " +
                "black root ${e2.javaClass.simpleName} ${e2.message}",
            e2
        )
        throw e2
    }
}

/** Binds Preview + capped/default ImageAnalysis for the panorama sweep. */
internal fun CameraViewModel.bindPanoUseCases(
    session: PanoSessionPrep,
    lensSelector: CameraSelector,
    capped: Boolean
): Camera {
    val analysisBuilder = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    if (capped) {
        analysisBuilder.setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(PANO_ANALYSIS_WIDTH, PANO_ANALYSIS_HEIGHT), // ~3 MP
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
        )
    }
    val analysis = analysisBuilder.build()
    imageAnalysis = analysis
    imageCapture = null // No ImageCapture in this session.
    return bindSession(session.provider, session.owner, lensSelector, session.preview, analysis)
}

/** Refreshes capabilities and marks the panorama session active. */
internal suspend fun CameraViewModel.finishPanoramaSession() {
    val zsP = boundCamera?.cameraInfo?.zoomState?.value
    Log.dev(
        "NightPreview",
        "setupPanoramaSession() bound zoom min=${zsP?.minZoomRatio} max=${zsP?.maxZoomRatio} " +
            "ratio=${zsP?.zoomRatio}"
    )
    boundCamera?.let { refreshCapabilities(it, boundLensIdField) }
    onSessionBound()
    photoSessionActiveMutable.value = true
    Log.dev(
        "NightPreview",
        "setupPanoramaSession() SUCCESS photoActive=true " +
            "surface=${surfaceRequestMutable.value?.resolution}"
    )
}
