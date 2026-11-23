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
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.data.saveVideoStaged
import com.vayunmathur.camera.data.setLastCaptureUri
import com.vayunmathur.camera.platform.camera2.RecordingSessionOwnership
import com.vayunmathur.camera.platform.camera2.SloMoEnginePolicy
import com.vayunmathur.camera.platform.camera2.SloMoRecordingReadiness
import com.vayunmathur.camera.platform.camera2.SloMoSaveAction
import com.vayunmathur.camera.platform.camera2.SloMoSavePathPolicy
import com.vayunmathur.library.log.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera2 migration Phase 5: slo-mo glue between the gated Camera2
 * high-speed session and the existing CameraX slo-mo path.
 *
 * The decision is evaluated only when a gate is on — with both gates off
 * this file costs one boolean check and SLOW_MO runs exactly as before.
 * With the gates on (the default), the lens snapshot, planner
 * selection and AVC encoder probe are assembled and [SloMoEnginePolicy]
 * decides; the reason is logged either way. Today the policy always keeps
 * recording on CameraX because `recordingEngineReady` is false: pieces
 * 2/4/7 are now in place (shared core, preview bridge, save-path glue),
 * but the output has not been device-verified (piece 6). Flipping the
 * gate therefore changes logging, never the recording path, until that
 * verification lands.
 */

/** Encoder mime the Phase-1 engine would record slo-mo with. */
private const val SLOMO_ENCODER_MIME = MediaFormat.MIMETYPE_VIDEO_AVC

/**
 * The recording configuration for [selection]: its size, playback rate
 * as the encoder frame rate, and capture rate as `KEY_CAPTURE_RATE`.
 * No-audio (CameraX slo-mo's contract, piece 5). Building this config
 * does not route any recording.
 */
internal fun HighSpeedSelection.toCustomRecordingConfig(): CustomRecordingConfig =
    CustomRecordingConfig(
        width = size.width,
        height = size.height,
        fps = playbackFps,
        captureFps = captureFps,
        videoMime = SLOMO_ENCODER_MIME,
        audioEnabled = false,
    )

/**
 * Builds the shared codec/muxer/drain core for one Camera2 slo-mo
 * recording (piece 2 + 7): externally owned, so the constrained
 * high-speed session — not a regular engine session — drives it.
 * The caller prepares its encoder surface before opening the session.
 */
internal fun buildCamera2SloMoRecordingCore(
    selection: HighSpeedSelection,
): Camera2RecordingCore = Camera2RecordingCore(
    config = selection.toCustomRecordingConfig(),
    initialBitrateBps = { SLOMO_RECORDING_BITRATE_BPS },
    ownership = RecordingSessionOwnership.EXTERNALLY_OWNED,
)

private const val SLOMO_RECORDING_BITRATE_BPS = 20_000_000

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
        recordingEngineReady = SloMoRecordingReadiness.recordingEngineReady,
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
        val facing = selectedLensMutable.value
            ?.takeIf { it.facing == LensFacing.BACK }
            ?.facing
            ?: availableLensesMutable.value
                .firstOrNull { it.facing == LensFacing.BACK }
                ?.facing
            ?: return
        val manager = app.getSystemService(CameraManager::class.java) ?: return
        // Only a CameraManager.cameraIdList id (mapped by facing) may be
        // handed to getCameraCharacteristics; the lens's CameraX-derived
        // logicalCameraId need not exist there (unknown-device crash).
        val lensId = CharacteristicsFacade.resolveCamera2Id(manager, facing) ?: return
        val facade = CharacteristicsFacade.tryFromCameraManager(manager, lensId) ?: return
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

/**
 * Save-path glue for one finished Camera2 slo-mo attempt (piece 7): a
 * readable, error-free staged file lands through the same
 * `saveVideoStaged` seam as every other Camera2/CameraX recording; a
 * failed attempt discards its file, latches this bind one-shot, and
 * rebinds the CameraX HFR ladder so the next press records on CameraX.
 * Runs its file I/O off-main. Returns the action taken (for tests/logs).
 */
internal fun CameraViewModel.handleCamera2SloMoFinalized(
    displayName: String,
    stagedFile: File,
    outcomeError: Boolean,
    outputReadable: Boolean,
    cameraId: String,
): SloMoSaveAction {
    val alreadyLatched = camera2SloMoFallbackBindKeyMutable.value ==
        com.vayunmathur.camera.platform.camera2.Camera2FallbackLatch
            .bindKey(cameraId, cameraModeMutable.value)
    val action = SloMoSavePathPolicy.actionFor(outcomeError, outputReadable, alreadyLatched)
    viewModelScope.launch {
        when (action) {
            SloMoSaveAction.SAVE_STAGED -> withContext(Dispatchers.IO) {
                if (stagedFile.exists()) {
                    saveVideoStaged(displayName, stagedFile)?.let { setLastCaptureUri(it) }
                    stagedFile.delete()
                }
            }
            SloMoSaveAction.DISCARD_AND_REBIND_CAMERAX -> {
                withContext(Dispatchers.IO) { stagedFile.delete() }
                // One-shot latch: only the first failure for this bind rebinds.
                if (onCamera2SloMoFailed(cameraId)) {
                    teardownSession()
                    setupHighSpeedSession()
                }
            }
            SloMoSaveAction.DISCARD_LATCHED -> withContext(Dispatchers.IO) {
                stagedFile.delete()
            }
        }
    }
    return action
}

/**
 * Rebinds the CameraX high-speed session after a Camera2 slo-mo attempt
 * failed before producing a file. One-shot per bind key, like preview's
 * latch; a repeat failure for the same key does not rebind-loop.
 */
internal fun CameraViewModel.rebindCameraXAfterSloMoFailure(cameraId: String) {
    if (!onCamera2SloMoFailed(cameraId)) return
    viewModelScope.launch {
        teardownSession()
        setupHighSpeedSession()
    }
}
