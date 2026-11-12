package com.vayunmathur.camera.util

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

/**
 * Binds a manual Preview + ImageCapture + ImageAnalysis session for the photo modes
 * (PHOTO / PORTRAIT / PANORAMA / PHOTOSPHERE / QR). ImageCapture requests the sensor's
 * maximum resolution; if the 3-stream max-res combination exceeds a device's stream-config
 * limits, it falls back to a default ImageCapture resolution. ImageAnalysis is always
 * capped (~1.2 MP) — see the note in [CameraViewModel.bindSession].
 */
suspend fun CameraViewModel.setupPhotoSession(): Boolean {
    Log.dev(
        "NightPreview",
        "setupPhotoSession() ENTRY thread=${Thread.currentThread().name} " +
            "lens=${lensFacingMutable.value} surfaceBefore=${surfaceRequestMutable.value?.resolution}"
    )
    return try {
        val session = preparePhotoSession() ?: return false
        bindPhotoLadder(session)
        finishPhotoSession()
        true
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupPhotoSession() OUTER CATCH – Failed to set up photo session – " +
                "solid black root? ${e.javaClass.simpleName} ${e.message}",
            e
        )
        false
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupPhotoSession() OUTER CATCH – Failed to set up photo session – " +
                "solid black root? ${e.javaClass.simpleName} ${e.message}",
            e
        )
        false
    }
}

/** Photo session scaffolding: provider, lenses, preview, owner, Ultra-HDR probe. */
internal data class PhotoSessionPrep(
    val provider: ProcessCameraProvider,
    val requestedLens: com.vayunmathur.camera.domain.PhysicalLens?,
    val lensFamily: List<com.vayunmathur.camera.domain.PhysicalLens>,
    val preview: Preview,
    val owner: ManualLifecycleOwner,
    val ultraHdrSupported: Boolean
)

/** Binds provider/owner/preview and probes Ultra HDR; the bind ladder runs next. */
internal suspend fun CameraViewModel.preparePhotoSession(): PhotoSessionPrep? {
    val provider = ProcessCameraProvider.awaitInstance(app)
    cameraProvider = provider
    Log.dev("NightPreview", "setupPhotoSession() got providerHash=${provider.hashCode()}")
    provider.unbindAll()

    ensureLensesEnumerated(provider)
    val requestedLens = selectedLensMutable.value
    val lensFamily = currentLensFamily()

    val previewBuilder = Preview.Builder()
    // Snapshot auto-converged AE ISO/exposure off the repeating preview requests so a
    // half-manual exposure can seed the un-set parameter.
    attachAeSnapshot(previewBuilder, "setupPhotoSession()")
    val preview = previewBuilder.build()
    preview.setSurfaceProvider { request ->
        Log.dev(
            "NightPreview",
            "setupPhotoSession() surfaceRequest emitted res=${request.resolution} " +
                "format=${request.javaClass.simpleName} thread=${Thread.currentThread().name} " +
                "nightActive=${nightModeActive.value}"
        )
        surfaceRequestMutable.value = request
    }
    Log.dev("NightPreview", "setupPhotoSession() preview surfaceProvider attached")

    val owner = ManualLifecycleOwner()
    owner.start()
    sessionLifecycleOwner = owner

    // Ultra HDR (JPEG with a gain map) when the sensor/pipeline supports it. Queried once
    // here; the bind ladder falls back to plain JPEG if the Ultra HDR combo can't bind.
    // Probed on the requested lens; a facing-only selector is the fallback probe.
    val ultraHdrSupported = probeUltraHdr(provider, requestedLens)
    return PhotoSessionPrep(provider, requestedLens, lensFamily, preview, owner, ultraHdrSupported)
}

/** Runs the max-res → default → plain-JPEG bind ladder and records the bound lens. */
internal suspend fun CameraViewModel.bindPhotoLadder(session: PhotoSessionPrep) {
    var boundLensId: String? = null
    boundCamera = try {
        val (lens, camera) = bindWithFallback(session.provider, session.requestedLens, session.lensFamily) {
            lensSel ->
            bindPhotoUseCases(session, lensSel, maxRes = true, ultraHdr = session.ultraHdrSupported)
        }
        boundLensId = lens?.logicalCameraId
        camera
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupPhotoSession() Max-res bind failed (was Warn, hidden); " +
                "retrying at default resolution. " +
                "This is where resolution becomes lower and cannot take full quality!",
            e
        )
        unbindQuietly(session.provider)
        bindPhotoFallback(
            session.provider,
            session.requestedLens,
            session.lensFamily,
            session.ultraHdrSupported,
            { lensSel, maxRes, ultraHdr ->
                bindPhotoUseCases(session, lensSel, maxRes = maxRes, ultraHdr = ultraHdr)
            }
        ) { lens ->
            boundLensId = lens?.logicalCameraId
        }
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupPhotoSession() Max-res bind failed (was Warn, hidden); " +
                "retrying at default resolution. " +
                "This is where resolution becomes lower and cannot take full quality!",
            e
        )
        unbindQuietly(session.provider)
        bindPhotoFallback(
            session.provider,
            session.requestedLens,
            session.lensFamily,
            session.ultraHdrSupported,
            { lensSel, maxRes, ultraHdr ->
                bindPhotoUseCases(session, lensSel, maxRes = maxRes, ultraHdr = ultraHdr)
            }
        ) { lens ->
            boundLensId = lens?.logicalCameraId
        }
    }
    boundLensIdField = boundLensId
}

/** Binds Preview + ImageCapture + capped ImageAnalysis for the photo session. */
internal fun CameraViewModel.bindPhotoUseCases(
    session: PhotoSessionPrep,
    lensSelector: CameraSelector,
    maxRes: Boolean,
    ultraHdr: Boolean
): Camera {
    Log.dev(
        "NightPreview",
        "setupPhotoSession() bind(maxRes=$maxRes ultraHdr=$ultraHdr) " +
            "START thread=${Thread.currentThread().name}"
    )
    return try {
        val capture = buildPhotoCapture(maxRes, ultraHdr)
        val analysis = buildPhotoAnalysis()
        bindSession(session.provider, session.owner, lensSelector, session.preview, capture, analysis).also {
            Log.dev(
                "NightPreview",
                "setupPhotoSession() bind SUCCESS res=${it.cameraInfo} " +
                    "zoom min=${it.cameraInfo.zoomState.value?.minZoomRatio} " +
                    "max=${it.cameraInfo.zoomState.value?.maxZoomRatio}"
            )
        }
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "setupPhotoSession() bind(maxRes=$maxRes ultraHdr=$ultraHdr) EXCEPTION – " +
                "root cause of black preview when fallback also fails",
            e
        )
        throw e
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "setupPhotoSession() bind(maxRes=$maxRes ultraHdr=$ultraHdr) EXCEPTION – " +
                "root cause of black preview when fallback also fails",
            e
        )
        throw e
    }
}

/** Builds the max/default-res ImageCapture with the still crop applied. */
internal fun CameraViewModel.buildPhotoCapture(maxRes: Boolean, ultraHdr: Boolean): ImageCapture {
    val selectorBuilder = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
    if (maxRes) {
        selectorBuilder.setAllowedResolutionMode(
            ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
        )
    }
    val captureBuilder = ImageCapture.Builder()
        .setResolutionSelector(selectorBuilder.build())
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

/**
 * Builds the capped (~1.2 MP) analysis stream, independently of [maxRes] (which stays
 * about ImageCapture — stills are unaffected and still come off the sensor at
 * full resolution). Nothing reading this stream benefits from sensor
 * resolution: PhotoAnalyzer samples average luminance, runs a ZXing decode
 * over the whole Y plane, and copies each frame via toBitmap() for the
 * Motion-Photo ring buffer, whose frames MotionPhotoEncoder then converts to
 * YUV 4:2:0 for the clip. All of that is paid per frame and scales with
 * area, so an uncapped stream made QR scanning and motion capture far more
 * expensive on high-end sensors for no quality gain. Night mode is unaffected:
 * captureNightBurst() shoots full-resolution frames through ImageCapture.
 */
internal fun CameraViewModel.buildPhotoAnalysis(): ImageAnalysis {
    val analysisBuilder = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(PHOTO_ANALYSIS_WIDTH, PHOTO_ANALYSIS_HEIGHT), // ~1.2 MP
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
        )
    val analysis = analysisBuilder.build()
    imageAnalysis = analysis
    return analysis
}

/** Applies manual controls, refreshes capabilities and marks the session active. */
internal suspend fun CameraViewModel.finishPhotoSession() {
    val zs = boundCamera?.cameraInfo?.zoomState?.value
    Log.dev(
        "NightPreview",
        "setupPhotoSession() bound zoomState min=${zs?.minZoomRatio} max=${zs?.maxZoomRatio} " +
            "ratio=${zs?.zoomRatio} thread=${Thread.currentThread().name}"
    )
    applyManualControls()
    boundCamera?.let { refreshCapabilities(it, boundLensIdField) }
    boundCamera?.cameraInfo?.let { observeNightModeIndicator(it) }
    onSessionBound()
    photoSessionActiveMutable.value = true
    Log.dev(
        "NightPreview",
        "setupPhotoSession() SUCCESS photoActive=true " +
            "surface=${surfaceRequestMutable.value?.resolution} " +
            "nightIndicatorSupported=$nightIndicatorSupported"
    )
}

/**
 * Default-resolution legs of the photo bind ladder (UltraHDR+default → JPEG+default),
 * shared by the two narrow catch blocks above so each stays a single statement.
 */
internal suspend fun CameraViewModel.bindPhotoFallback(
    provider: ProcessCameraProvider,
    requestedLens: com.vayunmathur.camera.domain.PhysicalLens?,
    lensFamily: List<com.vayunmathur.camera.domain.PhysicalLens>,
    ultraHdrSupported: Boolean,
    bind: (CameraSelector, Boolean, Boolean) -> Camera,
    onLens: (com.vayunmathur.camera.domain.PhysicalLens?) -> Unit,
): Camera {
    val secondTry: suspend (Boolean) -> Camera = { ultraHdr ->
        val (lens, camera) = bindWithFallback(provider, requestedLens, lensFamily) { lensSel ->
            bind(lensSel, false, ultraHdr)
        }
        onLens(lens)
        camera
    }
    try {
        return secondTry(ultraHdrSupported)
    } catch (e2: IllegalStateException) {
        return recoverFromFallbackFailure(provider, e2, ultraHdrSupported, secondTry)
    } catch (e2: IllegalArgumentException) {
        return recoverFromFallbackFailure(provider, e2, ultraHdrSupported, secondTry)
    }
}
