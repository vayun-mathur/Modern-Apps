package com.vayunmathur.camera.platform

import android.media.MediaFormat
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.vayunmathur.camera.domain.AdaptiveBitrateDecision
import com.vayunmathur.camera.domain.AdaptiveBitrateInput
import com.vayunmathur.camera.domain.AdaptiveVideoBitratePolicy
import com.vayunmathur.camera.domain.FrameDiffSample
import com.vayunmathur.camera.domain.ThermalPressure
import com.vayunmathur.camera.domain.VideoQualityTier
import com.vayunmathur.library.log.Log
import kotlin.math.roundToInt

/** Frame rate the ladder/boost probe is sized at (the video session's fixed rate). */
internal const val VIDEO_BITRATE_PROBE_FPS = 30

/** Feeds one analyzer sample into the estimator and publishes the classification. */
internal fun CameraViewModel.onSceneMotionSample(sample: FrameDiffSample) {
    val classification = sceneMotionEstimator.add(sample)
    if (classification != sceneMotionMutable.value) {
        sceneMotionMutable.value = classification
        Log.dev("VideoMotion", "scene motion=$classification samples=${sceneMotionEstimator.sampleCount}")
    }
}

/** Bytes free where recordings are staged (the cache dir), or null when the probe fails. */
internal fun CameraViewModel.probeFreeStorageBytes(): Long? = try {
    StatFs(app.cacheDir.absolutePath).availableBytes
} catch (e: IllegalArgumentException) {
    Log.status("AdaptiveBitrate", "StatFs probe failed", e)
    null
} catch (e: IllegalStateException) {
    Log.status("AdaptiveBitrate", "StatFs probe failed", e)
    null
}

/**
 * Thermal pressure from PowerManager: SEVERE only at THERMAL_STATUS_SEVERE or worse
 * (API 30+). Older devices, or any probe failure, report UNKNOWN, which the policy
 * treats as no evidence — it never forces a reduction.
 */
internal fun CameraViewModel.probeThermalPressure(): ThermalPressure {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ThermalPressure.UNKNOWN
    return try {
        val power = app.getSystemService(PowerManager::class.java) ?: return ThermalPressure.UNKNOWN
        if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            ThermalPressure.SEVERE
        } else {
            ThermalPressure.NORMAL
        }
    } catch (e: IllegalStateException) {
        Log.status("AdaptiveBitrate", "Thermal status probe failed", e)
        ThermalPressure.UNKNOWN
    } catch (e: IllegalArgumentException) {
        Log.status("AdaptiveBitrate", "Thermal status probe failed", e)
        ThermalPressure.UNKNOWN
    } catch (e: SecurityException) {
        Log.status("AdaptiveBitrate", "Thermal status probe failed", e)
        ThermalPressure.UNKNOWN
    }
}

/**
 * The adaptive decision for [baseTier] from live probes: the estimator's latest
 * classification, free storage, thermal status, and (only when a boost could
 * apply) the encoder range probe for the codec the Recorder will be built with.
 */
internal fun CameraViewModel.computeAdaptiveBitrateDecision(
    baseTier: VideoQualityTier,
    useAv1: Boolean,
    useHevc: Boolean,
): AdaptiveBitrateDecision {
    val boostedBps = (baseTier.baseBitrateBps *
        AdaptiveVideoBitratePolicy.HIGH_MOTION_BOOST_FACTOR).roundToInt()
    val mime = when {
        useAv1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        useHevc -> MediaFormat.MIMETYPE_VIDEO_HEVC
        else -> MediaFormat.MIMETYPE_VIDEO_AVC
    }
    val boostAllowed = CodecSupport.supportsVideoBitrate(
        mime, baseTier.width, baseTier.height, VIDEO_BITRATE_PROBE_FPS, boostedBps
    )
    val decision = AdaptiveVideoBitratePolicy.decide(
        AdaptiveBitrateInput(
            baseTier = baseTier,
            motion = sceneMotionEstimator.lastClassification,
            freeStorageBytes = probeFreeStorageBytes(),
            thermal = probeThermalPressure(),
            boostAllowed = boostAllowed,
        )
    )
    lastAdaptiveBitrateDecision = decision
    Log.debug(
        "AdaptiveBitrate",
        "tier=${decision.baseTier}->${decision.effectiveTier} motion=${decision.motion} " +
            "bitrate=${decision.bitrateBps} reductions=${decision.reductions} boosted=${decision.boosted}"
    )
    return decision
}

/**
 * Builds the recording engine for [path] behind the [VideoRecordingEngine]
 * boundary. The CameraX engine adapts the existing session plumbing (its
 * finalization keeps flowing through VideoRecordEvent); the Camera2 engine is
 * constructed only when the selection rule picks it, and owns its own
 * device/session from that point. Record-start selection itself lives in
 * [recordingPathFor]; nothing constructs the Camera2 engine while the gate is off.
 */
internal fun CameraViewModel.createVideoRecordingEngine(
    path: com.vayunmathur.camera.domain.RecordingPath,
    config: CustomRecordingConfig,
    controller: com.vayunmathur.camera.domain.LiveBitrateController,
): VideoRecordingEngine = when (path) {
    com.vayunmathur.camera.domain.RecordingPath.CUSTOM_CAMERA2 -> Camera2VideoRecordingEngine(
        context = app,
        config = config,
        controller = controller,
        motionSupplier = { sceneMotionEstimator.lastClassification },
        onMotionSample = { sample -> onSceneMotionSample(sample) },
        onError = { e -> Log.error("Camera2Recorder", "Camera2 recording engine error", e) },
    )
    com.vayunmathur.camera.domain.RecordingPath.CAMERAX_RECORDER -> CameraXVideoRecordingEngine(
        startImpl = { startRecording() },
        stopImpl = {
            currentRecording?.stop()
            currentRecording = null
            stopRecordingTimer()
        },
    )
}

/** CameraX quality -> pure ladder tier; unknown quality keeps the FHD rung (as before). */
internal fun tierForQuality(quality: androidx.camera.video.Quality?): VideoQualityTier = when (quality) {
    androidx.camera.video.Quality.UHD -> VideoQualityTier.UHD
    androidx.camera.video.Quality.FHD -> VideoQualityTier.FHD
    androidx.camera.video.Quality.HD -> VideoQualityTier.HD
    androidx.camera.video.Quality.SD -> VideoQualityTier.SD
    else -> VideoQualityTier.FHD
}
