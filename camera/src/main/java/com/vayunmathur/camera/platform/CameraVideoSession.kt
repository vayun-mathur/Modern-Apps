package com.vayunmathur.camera.platform
import android.annotation.SuppressLint

import android.media.MediaFormat
import com.vayunmathur.library.log.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.vayunmathur.camera.platform.currentLensFamily
import com.vayunmathur.camera.platform.ensureLensesEnumerated
import com.vayunmathur.camera.platform.lensSelector
import com.vayunmathur.camera.platform.refreshCapabilities
import com.vayunmathur.camera.data.setFlashMode

suspend fun CameraViewModel.setupVideoSession(): Boolean {
    return try {
        val session = prepareVideoSession()
        bindVideoLadder(session)
        finishVideoSession()
        true
    } catch (e: IllegalStateException) {
        Log.error("VideoSession", "Failed to set up video session", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error("VideoSession", "Failed to set up video session", e)
        false
    }
}

/** Codec/quality choices resolved off the camera info. */
internal data class VideoChoices(
    val useAv1: Boolean,
    val useHevc: Boolean,
    val useOpus: Boolean,
    val hlgSupported: Boolean,
    val bestFpsRange: android.util.Range<Int>?,
    val stabilizationMode: Int?,
    val qualitySelector: QualitySelector
)

/** Video session scaffolding: provider, lens, owner, choices. */
internal data class VideoSessionPrep(
    val provider: ProcessCameraProvider,
    val videoLens: com.vayunmathur.camera.domain.PhysicalLens?,
    val videoFamily: List<com.vayunmathur.camera.domain.PhysicalLens>,
    val owner: ManualLifecycleOwner,
    val choices: VideoChoices
)

/** Binds provider/owner and resolves codec + tuning choices. */
internal suspend fun CameraViewModel.prepareVideoSession(): VideoSessionPrep {
    val provider = ProcessCameraProvider.awaitInstance(app)
    cameraProvider = provider
    provider.unbindAll()

    ensureLensesEnumerated(provider)
    val videoLens = selectedLensMutable.value
    val videoFamily = currentLensFamily()
    val selector = lensSelector(lensFacingMutable.value, videoLens)
    val cameraInfo = provider.getCameraInfo(selector)

    val owner = ManualLifecycleOwner()
    owner.start()
    sessionLifecycleOwner = owner
    return VideoSessionPrep(provider, videoLens, videoFamily, owner, resolveVideoChoices(cameraInfo))
}

/** Resolves codec selection (AV1 > HEVC > AVC) + fps/stabilization/quality tuning. */
internal fun CameraViewModel.resolveVideoChoices(cameraInfo: androidx.camera.core.CameraInfo): VideoChoices {
    val selectedCodec = videoCodecMutable.value
    // Respect user setting with fallback: AV1 > HEVC > AVC priority for availability check.
    // If selected codec isn't available, fall back to next best available.
    val useAv1 = selectedCodec == VideoCodec.AV1 &&
        CodecSupport.isHardwareAv1EncoderAvailable && av1SupportedByCamera(cameraInfo)
    val useHevc = !useAv1 && selectedCodec != VideoCodec.AVC &&
        CodecSupport.isHevcEncoderAvailable && hevcSupportedByCamera(cameraInfo)
    val useOpus = false
    // HLG10 (10-bit HDR) when the camera's video pipeline supports it. Uses the default
    // HEVC/H.264 codec (AV1 stays off). Preview and VideoCapture must share the dynamic
    // range or the bind fails, so both are gated together.
    val hlgSupported = hlgSupportedByCamera(cameraInfo)
    Log.debug("VideoSession", "Codec selection: av1=$useAv1, hevc=$useHevc, opus=$useOpus, hlg10=$hlgSupported")

    // Cinematic mode enables video stabilization; all video modes always record at max
    // quality/fps (no UI picker).
    val cinematic = cameraModeMutable.value == CameraMode.CINEMATIC
    val bestFpsRange = highestFpsRange(cameraInfo)
    val stabilizationMode = if (cinematic) preferredStabilizationMode(cameraInfo) else null
    Log.debug("VideoSession", "Video tuning: fps=$bestFpsRange, stabilization=$stabilizationMode")

    // Prefer UHD, then FHD, then HD, falling back to the next lower supported quality.
    val qualitySelector = QualitySelector.fromOrderedList(
        listOf(Quality.UHD, Quality.FHD, Quality.HD),
        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
    )
    return VideoChoices(useAv1, useHevc, useOpus, hlgSupported, bestFpsRange, stabilizationMode, qualitySelector)
}

/**
 * Runs the video bind ladder across HDR/snapshot rungs, each across the lens ladder.
 * Bind ladder: prefer HDR + snapshot, then drop the snapshot use case, then drop HDR.
 * Each rung is tried across the lens ladder (requested → wide → any same-facing).
 */
internal fun CameraViewModel.bindVideoLadder(session: VideoSessionPrep) {
    var bound: Camera? = null
    var videoBoundLensId: String? = null
    var lastError: Exception? = null
    for ((hlg, snapshot) in videoAttempts(session.choices.hlgSupported)) {
        val rung = tryVideoRungSet(session, hlg, snapshot)
        lastError = rung.lastError ?: lastError
        if (rung.bound != null) {
            videoBoundLensId = rung.lensId
            bound = rung.bound
            break
        }
    }
    boundCamera = bound ?: throw (lastError ?: IllegalStateException("Video session bind failed"))
    boundLensIdField = videoBoundLensId
}

/** HDR/snapshot rungs: prefer HDR + snapshot, then drop snapshot, then drop HDR. */
internal fun videoAttempts(hlgSupported: Boolean): List<Pair<Boolean, Boolean>> {
    return buildList {
        add(hlgSupported to true)
        add(hlgSupported to false)
        if (hlgSupported) {
            add(false to true)
            add(false to false)
        }
    }
}

/** Outcome of one video rung across the lens ladder. */
internal data class VideoRungResult(
    val bound: Camera?,
    val lensId: String?,
    val lastError: Exception?
)

/** Tries one HDR/snapshot rung across the lens ladder (requested → wide → any). */
internal fun CameraViewModel.tryVideoRungSet(
    session: VideoSessionPrep,
    hlg: Boolean,
    snapshot: Boolean
): VideoRungResult {
    val lensOrdered = buildList {
        if (session.videoLens != null) add(session.videoLens)
        session.videoFamily.sortedBy { it.fallbackPriority }
            .forEach { if (it != session.videoLens) add(it) }
        if (session.videoLens == null && session.videoFamily.isEmpty()) add(null)
    }
    var lastError: Exception? = null
    for (candidate in lensOrdered) {
        if (tryVideoRung(session, candidate, hlg, snapshot)) {
            return VideoRungResult(boundCamera, candidate?.logicalCameraId, lastError)
        } else {
            lastError = videoLastError
        }
    }
    return VideoRungResult(null, null, lastError)
}

/** Last video bind error, published by [tryVideoRung] for the ladder report. */
internal var videoLastError: Exception? = null

/** Tries one video rung on one lens; true when it binds. */
internal fun CameraViewModel.tryVideoRung(
    session: VideoSessionPrep,
    candidate: com.vayunmathur.camera.domain.PhysicalLens?,
    hlg: Boolean,
    snapshot: Boolean
): Boolean {
    return try {
        session.provider.unbindAll()
        boundCamera = bindVideoUseCases(
            session,
            lensSelector(lensFacingMutable.value, candidate),
            hlg,
            snapshot
        )
        true
    } catch (e: IllegalStateException) {
        videoLastError = e
        Log.status(
            "VideoSession",
            "Video bind failed (hlg=$hlg, snapshot=$snapshot, " +
                "lens=${candidate?.labelKey}); trying next",
            e
        )
        false
    } catch (e: IllegalArgumentException) {
        videoLastError = e
        Log.status(
            "VideoSession",
            "Video bind failed (hlg=$hlg, snapshot=$snapshot, " +
                "lens=${candidate?.labelKey}); trying next",
            e
        )
        false
    }
}

/** Binds Preview + VideoCapture (+ optional snapshot ImageCapture) for one rung. */
internal fun CameraViewModel.bindVideoUseCases(
    session: VideoSessionPrep,
    lensSelector: CameraSelector,
    hlg: Boolean,
    snapshot: Boolean
): Camera {
    val choices = session.choices
    val dynamicRange = if (hlg) androidx.camera.core.DynamicRange.HLG_10_BIT
        else androidx.camera.core.DynamicRange.SDR
    val preview = buildVideoPreview(session, dynamicRange)
    val capture = buildVideoCapture(session, dynamicRange)
    return if (snapshot) {
        // Extra ImageCapture use case enables taking a still while recording (SDR JPEG).
        // Crop matches the still aspect ratio like the photo session, or snapshots
        // always save full-frame while the preview shows cropped.
        val still = ImageCapture.Builder()
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        imageCapture = still
        still.setCropAspectRatio(currentCropAspectRatio())
        bindSession(session.provider, session.owner, lensSelector, preview, capture, still)
    } else {
        imageCapture = null
        bindSession(session.provider, session.owner, lensSelector, preview, capture)
    }
}

/** Builds the video preview with the shared dynamic range + capture options. */
internal fun CameraViewModel.buildVideoPreview(
    session: VideoSessionPrep,
    dynamicRange: androidx.camera.core.DynamicRange
): Preview {
    val previewBuilder = Preview.Builder()
        .setDynamicRange(dynamicRange)
    applyVideoCaptureRequestOptions(
        previewBuilder,
        session.choices.bestFpsRange,
        session.choices.stabilizationMode
    )
    val preview = previewBuilder.build()
    preview.setSurfaceProvider { request -> surfaceRequestMutable.value = request }
    return preview
}

/** Builds the video capture with codec/mirror/dynamic-range + capture options. */
@SuppressLint("RestrictedApi") // Recorder.Builder.setAudioSource(Int) is CameraX's documented public entry point for the mic source; the restriction is on the AudioSpec IntDef constants, not this builder method, and there is no public alternative.
internal fun CameraViewModel.buildVideoCapture(
    session: VideoSessionPrep,
    dynamicRange: androidx.camera.core.DynamicRange
): VideoCapture<Recorder> {
    val choices = session.choices
    val recorderBuilder = Recorder.Builder()
        .setQualitySelector(choices.qualitySelector)
        .setAudioSource(audioInputSourceMutable.value.specValue)
    if (choices.useAv1) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AV1)
    else if (choices.useHevc) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    if (choices.useOpus) recorderBuilder.setAudioMimeType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    val captureBuilder = VideoCapture.Builder(recorderBuilder.build())
        .setMirrorMode(
            if (mirrorFrontMutable.value) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY
            else MirrorMode.MIRROR_MODE_OFF
        )
        .setDynamicRange(dynamicRange)
    applyVideoCaptureRequestOptions(
        captureBuilder,
        choices.bestFpsRange,
        choices.stabilizationMode
    )
    val capture = captureBuilder.build()
    videoCapture = capture
    recordingWithAv1 = choices.useAv1
    recordingWithHevc = choices.useHevc
    return capture
}

/** Records snapshot support and marks the video session active. */
internal suspend fun CameraViewModel.finishVideoSession() {
    videoSnapshotSupportedMutable.value = imageCapture != null
    boundCamera?.let { refreshCapabilities(it, boundLensIdField) }
    videoSessionActiveMutable.value = true
}
