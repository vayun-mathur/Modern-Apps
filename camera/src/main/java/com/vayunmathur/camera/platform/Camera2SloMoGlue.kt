package com.vayunmathur.camera.platform

import android.hardware.camera2.CameraManager
import android.media.MediaFormat
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.HighSpeedPlanner
import com.vayunmathur.camera.platform.camera2.HighSpeedSelection
import com.vayunmathur.camera.platform.camera2.SloMoCamera2Gate
import com.vayunmathur.camera.platform.camera2.SloMoEngine
import com.vayunmathur.camera.platform.camera2.SloMoEngineInput
import com.vayunmathur.camera.platform.camera2.SloMoEnginePolicy
import com.vayunmathur.library.log.Log

/**
 * Camera2 migration Phase 5: slo-mo glue between the gated Camera2
 * high-speed session and the existing CameraX slo-mo path.
 *
 * The decision is evaluated only when a gate is on — with both gates off
 * (the default) this file costs one boolean check and SLOW_MO runs exactly
 * as before. When the runtime gate is on, the lens snapshot, planner
 * selection and AVC encoder probe are assembled and [SloMoEnginePolicy]
 * decides; the reason is logged either way. Today the policy always keeps
 * recording on CameraX because `recordingEngineReady` is false: the
 * constrained session (`Camera2HighSpeedSession`) is not yet coupled to
 * the Phase-1 codec/muxer engine and the output has not been
 * device-verified. Flipping the gate therefore changes logging, never the
 * recording path, until that coupling lands.
 */

/** Encoder mime the Phase-1 engine would record slo-mo with. */
private const val SLOMO_ENCODER_MIME = MediaFormat.MIMETYPE_VIDEO_AVC

/**
 * The Phase-1 engine configuration for [selection]: its size, playback
 * rate as the encoder frame rate, and capture rate as `KEY_CAPTURE_RATE`.
 * Building this config does not route any recording — the engine still
 * opens its own (regular) device, so the policy's
 * `recordingEngineReady` stays false until it can be driven by
 * `Camera2HighSpeedSession`'s constrained session.
 */
internal fun HighSpeedSelection.toCustomRecordingConfig(): CustomRecordingConfig =
    CustomRecordingConfig(
        width = size.width,
        height = size.height,
        fps = playbackFps,
        captureFps = captureFps,
        videoMime = SLOMO_ENCODER_MIME,
    )

/** The Camera2 slo-mo decision for one lens snapshot; pure but for logging. */
internal fun CameraViewModel.camera2SloMoDecision(
    facade: CharacteristicsFacade,
    encoderSupportsCaptureRate: Boolean,
): SloMoEngine {
    val snapshot = facade.snapshot
    val selection = HighSpeedPlanner.select(snapshot)
    val input = SloMoEngineInput(
        camera2Enabled = SloMoCamera2Gate.ENABLED && camera2SloMoEnabledMutable.value,
        mode = cameraModeMutable.value,
        lensFacing = snapshot.lensFacing,
        hasConstrainedHighSpeed = snapshot.supportsConstrainedHighSpeedVideo,
        hasSelection = selection != null,
        encoderSupportsCaptureRate = encoderSupportsCaptureRate,
        recordingEngineReady = false,
    )
    val reason = SloMoEnginePolicy.exclusionReason(input)
    Log.debug(
        "SloMo",
        "Camera2 slo-mo decision: engine=${SloMoEnginePolicy.select(input)} " +
            "selection=$selection reason=${reason ?: "none"}",
    )
    return SloMoEnginePolicy.select(input)
}

/**
 * True when some AVC encoder can run at [selection]'s capture rate at its
 * size — the encoder-at-HFR half of the Phase-5 decision. Bitrate is not
 * probed here (the Phase-1 controller owns it); size + frame rate are.
 */
internal fun encoderSupportsSloMoCaptureRate(selection: HighSpeedSelection): Boolean =
    CodecSupport.supportsVideoFrameRate(
        SLOMO_ENCODER_MIME,
        selection.size.width,
        selection.size.height,
        selection.captureFps,
    )

/**
 * Evaluates (and logs) the Camera2 slo-mo decision for the requested back
 * lens when the runtime gate is on; a no-op otherwise. Never routes a
 * recording itself — the CameraX setup that follows remains the live path
 * while the policy excludes Camera2 (see the file doc).
 */
internal fun CameraViewModel.evaluateCamera2SloMoDecision() {
    if (!camera2SloMoEnabledMutable.value) return
    try {
        val lensId = selectedLensMutable.value
            ?.takeIf { it.facing == LensFacing.BACK }
            ?.logicalCameraId
            ?: availableLensesMutable.value
                .firstOrNull { it.facing == LensFacing.BACK }
                ?.logicalCameraId
            ?: return
        val manager = app.getSystemService(CameraManager::class.java) ?: return
        val facade = CharacteristicsFacade.fromCameraManager(manager, lensId)
        val selection = HighSpeedPlanner.select(facade.snapshot)
        camera2SloMoDecision(
            facade,
            encoderSupportsCaptureRate = selection?.let {
                encoderSupportsSloMoCaptureRate(it)
            } ?: false,
        )
    } catch (e: IllegalStateException) {
        Log.status("SloMo", "Camera2 slo-mo decision failed; staying on CameraX", e)
    } catch (e: IllegalArgumentException) {
        Log.status("SloMo", "Camera2 slo-mo decision failed; staying on CameraX", e)
    }
}
