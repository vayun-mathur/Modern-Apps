package com.vayunmathur.camera.platform

import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

import com.vayunmathur.library.log.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.platform.ensureLensesEnumerated
import com.vayunmathur.camera.platform.lensSelector
import com.vayunmathur.camera.platform.refreshCapabilities

/**
 * Sets up a true high-speed (HFR) session for Slo-Mo.
 *
 * Requirements per product spec:
 * - Back camera only (no front-camera / selfie Slo-Mo)
 * - No normal-speed fallbacks: only bind true HFR (slowMotionEnabled=true); if it fails,
 *   return false so the caller knows the device can't do Slo-Mo.
 * - Quality handling is Slo-Mo-only and does NOT affect VIDEO/PHOTO/etc (those use their own
 *   QualitySelector in setupVideoSession/setupPhotoSession). This method's quality selection
 *   only touches the HFR Recorder built here.
 */
suspend fun CameraViewModel.setupHighSpeedSession(): Boolean {
    return try {
        val session = prepareHighSpeedSession() ?: return false
        if (!bindHfrLadder(session)) return false
        applyAntiBanding()
        finishHighSpeedSession()
        true
    } catch (e: IllegalStateException) {
        Log.error("SloMo", "Failed to set up high-speed session", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error("SloMo", "Failed to set up high-speed session", e)
        false
    }
}

/** Minimum fps that counts as true HFR; preferred Slo-Mo quality order. */
private const val HFR_MIN_FPS = 60
private val SLOMO_QUALITY_ORDER = listOf(Quality.FHD, Quality.HD, Quality.UHD, Quality.SD)

/** High-speed session scaffolding: provider, lens, use cases, HFR ranges. */
internal data class HighSpeedPrep(
    val provider: ProcessCameraProvider,
    val requestedSloMoLens: com.vayunmathur.camera.domain.PhysicalLens?,
    val sloMoFamily: List<com.vayunmathur.camera.domain.PhysicalLens>,
    val preview: Preview,
    val videoCapture: VideoCapture<Recorder>,
    val orderedQualities: List<Quality>,
    val hfrRanges: List<android.util.Range<Int>>
)

/** Enforces back camera, builds HFR use cases and resolves the HFR range list. */
internal suspend fun CameraViewModel.prepareHighSpeedSession(): HighSpeedPrep? {
    val provider = ProcessCameraProvider.awaitInstance(app)
    cameraProvider = provider
    provider.unbindAll()

    // Enforce back camera — Slo-Mo is disallowed on front.
    if (lensFacingMutable.value != CameraSelector.LENS_FACING_BACK) {
        lensFacingMutable.value = CameraSelector.LENS_FACING_BACK
    }
    ensureLensesEnumerated(provider)
    // Slo-Mo binds the requested back lens when possible (HFR is often wide-only);
    // each HFR range attempt below retries across the back family.
    val requestedSloMoLens = selectedLensMutable.value?.takeIf { it.facing == LensFacing.BACK }
    val sloMoFamily = LensSelectionLogic.filterByFacing(availableLensesMutable.value, LensFacing.BACK)
    val selector = lensSelector(CameraSelector.LENS_FACING_BACK, requestedSloMoLens)

    val cameraInfo = provider.getCameraInfo(selector)
    val capabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo)
        ?: run {
            Log.debug("SloMo", "High-speed video not supported on back camera on this device")
            sloMoSupportedMutable.value = false
            return null
        }
    val orderedQualities = resolveHfrQualities(capabilities) ?: return null
    val videoCapture = buildHfrCapture(orderedQualities)
    val hfrRanges = resolveHfrRanges(cameraInfo, videoCapture) ?: return null
    return HighSpeedPrep(
        provider,
        requestedSloMoLens,
        sloMoFamily,
        videoCapture.first,
        videoCapture.second,
        orderedQualities,
        hfrRanges
    )
}

/**
 * Resolves the HFR quality list (SDR-only, FHD/HD preferred); null when unsupported.
 *
 * Only looks at SDR qualities supported by the HFR path — this is Slo-Mo-local.
 * Bug fix for Pixel 8 black screen: default VideoCapture quality (often UHD) is
 * not supported for HFR on many devices; filtering to supported HFR qualities fixes bind.
 */
internal fun CameraViewModel.resolveHfrQualities(
    capabilities: androidx.camera.video.VideoCapabilities
): List<Quality>? {
    val supportedQualities = capabilities.getSupportedQualities(
        androidx.camera.core.DynamicRange.SDR
    )
    Log.debug("SloMo", "High-speed supported qualities (back): $supportedQualities")

    if (supportedQualities.isEmpty()) {
        Log.error("SloMo", "High-speed reports no supported qualities")
        sloMoSupportedMutable.value = false
        return null
    }

    // Build a QualitySelector using ONLY qualities that HFR actually supports.
    // Prefer FHD/HD which are the typical slo-mo resolutions on Pixel 8 (1080p 120/240fps).
    // This never touches other modes' quality selectors.
    return SLOMO_QUALITY_ORDER.filter { it in supportedQualities }
        .ifEmpty { supportedQualities.toList() }
}

/** Builds the HFR preview + VideoCapture; returns preview and capture. */
internal fun CameraViewModel.buildHfrCapture(
    orderedQualities: List<Quality>
): Pair<Preview, VideoCapture<Recorder>> {
    val qualitySelector = QualitySelector.fromOrderedList(
        orderedQualities,
        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
    )

    val preview = Preview.Builder().build()
    preview.setSurfaceProvider { request -> surfaceRequestMutable.value = request }

    val recorder = Recorder.Builder()
        .setQualitySelector(qualitySelector)
        .build()
    // MirrorMode is not allowed for high-speed video (HighSpeedVideoSessionConfig
    // validates and throws IllegalArgumentException). Slo-Mo is back-only anyway.
    val videoCapture = VideoCapture.Builder(recorder).build()
    highSpeedVideoCapture = videoCapture
    return preview to videoCapture
}

/**
 * Queries HFR ranges via a temp config and keeps true-HFR (>=60fps) ones,
 * highest fps first. Null when none are available.
 */
internal fun CameraViewModel.resolveHfrRanges(
    cameraInfo: androidx.camera.core.CameraInfo,
    videoCapture: Pair<Preview, VideoCapture<Recorder>>
): List<android.util.Range<Int>>? {
    // Query available HFR frame-rate ranges via a temp config.
    val tempConfig = HighSpeedVideoSessionConfig.Builder(videoCapture.second)
        .setPreview(videoCapture.first)
        .setSlowMotionEnabled(true)
        .build()
    val ranges = queryHfrRanges(cameraInfo, tempConfig)
    Log.debug("SloMo", "High-speed supported frame rate ranges: $ranges")

    if (ranges.isEmpty()) {
        Log.error("SloMo", "No high-speed frame rate ranges available")
        sloMoSupportedMutable.value = false
        return null
    }

    // Try ranges from highest fps downwards — Pixel 8 back: 240fps, 120fps.
    // No normal-speed (<=30fps) fallbacks allowed; only true HFR >= 60fps.
    val hfrRanges = ranges.filter { it.upper >= HFR_MIN_FPS }.sortedByDescending { it.upper }
    if (hfrRanges.isEmpty()) {
        Log.error("SloMo", "No true HFR (>=60fps) ranges found")
        sloMoSupportedMutable.value = false
        return null
    }
    return hfrRanges.toList()
}

/** Queries HFR frame-rate ranges; empty when unreadable. */
internal fun CameraViewModel.queryHfrRanges(
    cameraInfo: androidx.camera.core.CameraInfo,
    tempConfig: HighSpeedVideoSessionConfig
): List<android.util.Range<Int>> {
    return try {
        cameraInfo.getSupportedFrameRateRanges(tempConfig).toList()
    } catch (e: IllegalStateException) {
        Log.status("SloMo", "Failed to query HFR ranges", e)
        emptyList()
    } catch (e: IllegalArgumentException) {
        Log.status("SloMo", "Failed to query HFR ranges", e)
        emptyList()
    }
}

/**
 * Tries HFR ranges from highest fps downwards, each across the back lens ladder.
 * False when nothing binds (caller marks Slo-Mo unsupported).
 */
internal fun CameraViewModel.bindHfrLadder(session: HighSpeedPrep): Boolean {
    var boundSloMoLensId: String? = null
    var lastError: Exception? = null
    var bound = false
    // Try HFR ranges from highest fps downwards, each across the back lens ladder.
    val lensOrdered = buildList {
        if (session.requestedSloMoLens != null) add(session.requestedSloMoLens)
        session.sloMoFamily.sortedBy { it.fallbackPriority }
            .forEach { if (it != session.requestedSloMoLens) add(it) }
        if (session.requestedSloMoLens == null && session.sloMoFamily.isEmpty()) add(null)
    }
    outer@ for (range in session.hfrRanges) {
        for (candidate in lensOrdered) {
            if (tryHfrBind(session, range, candidate)) {
                boundSloMoLensId = candidate?.logicalCameraId
                bound = true
                break@outer
            } else {
                lastError = hfrLastError
            }
        }
    }

    if (!bound) {
        Log.error("SloMo", "All HFR ranges failed, last error: $lastError")
        sloMoSupportedMutable.value = false
        return false
    }
    boundLensIdField = boundSloMoLensId
    return true
}

/** Last HFR bind error, published by [tryHfrBind] for the ladder report. */
internal var hfrLastError: Exception? = null

/** Tries one HFR range on one lens; true when it binds and arms the session. */
internal fun CameraViewModel.tryHfrBind(
    session: HighSpeedPrep,
    range: android.util.Range<Int>,
    candidate: com.vayunmathur.camera.domain.PhysicalLens?
): Boolean {
    return try {
        session.provider.unbindAll()
        // Re-attach surface provider after unbindAll for preview to re-emit request
        session.preview.setSurfaceProvider { request -> surfaceRequestMutable.value = request }

        val configBuilder = HighSpeedVideoSessionConfig.Builder(session.videoCapture)
            .setPreview(session.preview)
            .setSlowMotionEnabled(true)
            .setFrameRateRange(range)
            .setAutoRotationEnabled(true)

        val owner = ManualLifecycleOwner()
        owner.start()
        sessionLifecycleOwner?.destroy()
        sessionLifecycleOwner = owner

        boundCamera = session.provider.bindToLifecycle(
            owner,
            lensSelector(CameraSelector.LENS_FACING_BACK, candidate),
            configBuilder.build()
        )
        sloMoFps = range.upper
        Log.dev(
            "SloMo",
            "High-speed session bound at ${range.upper}fps (range=$range) " +
                "lens=${candidate?.labelKey}, quality=${session.orderedQualities}"
        )
        return true
    } catch (e: IllegalStateException) {
        hfrLastError = e
        Log.status("SloMo", "Failed to bind HFR at $range lens=${candidate?.labelKey}, trying next", e)
        sessionLifecycleOwner?.destroy()
        sessionLifecycleOwner = null
        false
    } catch (e: IllegalArgumentException) {
        hfrLastError = e
        Log.status("SloMo", "Failed to bind HFR at $range lens=${candidate?.labelKey}, trying next", e)
        sessionLifecycleOwner?.destroy()
        sessionLifecycleOwner = null
        false
    }
}

/** Sets anti-banding to reduce flicker under artificial light. */
@Suppress("DEPRECATION")
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.applyAntiBanding() {
    try {
        val cam2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(
            boundCamera!!.cameraControl
        )
        cam2Control.setCaptureRequestOptions(
            androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                )
                .build()
        )
    } catch (e: IllegalArgumentException) {
        Log.status("SloMo", "Could not set anti-banding", e)
    }
}

/** Refreshes capabilities and marks the high-speed session active. */
internal suspend fun CameraViewModel.finishHighSpeedSession() {
    val zoomAfter = boundCamera?.cameraInfo?.zoomState?.value
    Log.dev(
        "NightPreview",
        "setupHighSpeedSession() after levels=${availableZoomLevelsMutable.value} " +
            "ratio=${zoomRatioMutable.value} min=${zoomAfter?.minZoomRatio} max=${zoomAfter?.maxZoomRatio}"
    )
    boundCamera?.let { refreshCapabilities(it, boundLensIdField) }
    sloMoSupportedMutable.value = true
    highSpeedActiveMutable.value = true
}
