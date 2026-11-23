package com.vayunmathur.camera.platform

import android.hardware.camera2.CameraManager
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.data.setLastCaptureUri
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.camera2.Camera2ExtensionProbe
import com.vayunmathur.camera.platform.camera2.Camera2ExtensionSession
import com.vayunmathur.camera.platform.camera2.CapturedStill
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.ExtensionsEngineInput
import com.vayunmathur.camera.platform.camera2.ExtensionsEnginePolicy
import com.vayunmathur.camera.platform.camera2.NightEngine
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Camera2 migration Phase 6: NIGHT-extension glue between the gated
 * [Camera2ExtensionSession] and the existing night routing.
 *
 * Decision order (see [ExtensionsEnginePolicy]): Camera2 extension only
 * when the gate is on, the [Camera2ExtensionProbe] passes for the current
 * lens, and the failure cache is clear; else the CameraX extension; else
 * the own burst — the last two exactly as before this phase.
 *
 * Failure-cache reuse, not a fork: the persisted 7-day cache lives in
 * `CameraNightMode.kt` (`night_ext_failed_at`, [nightExtFailedRecently] /
 * [recordNightExtensionFailure]). This glue reads that store for every
 * decision and records Camera2 bind/capture failures into that same store,
 * so a GrapheneOS-style async extension failure suppresses both engines
 * for the same TTL as a CameraX failure does today.
 *
 * Detection: there is no Camera2 `getNightModeIndicator()` equivalent, so
 * engagement on this path is the existing luma hysteresis (PhotoAnalyzer
 * average-Y → `onLuminance`, which only skips when a CameraX indicator was
 * observed). This glue never observes an indicator; the moon affordance
 * and `nightModeActive` therefore behave identically whichever engine
 * will serve the capture.
 *
 * Strength UI: there is no Camera2 strength flow, so a Camera2 decision
 * clears the strength value and the screen state hides the indicator on
 * this path ([ExtensionsEnginePolicy.showsExtensionStrength]) — it is
 * never synthesised from the probe's latency estimate.
 */

/** Registers the live gated Camera2 extension session (null when it leaves composition). */
internal fun CameraViewModel.registerCamera2ExtensionSession(session: Camera2ExtensionSession?) {
    activeCamera2ExtensionSession = session
    camera2NightSessionActiveMutable.value = session != null
    if (session != null) {
        // No Camera2 strength flow: never carry a CameraX strength value over.
        extensionStrengthMutable.value = null
    }
}

/** The Camera2 night decision for one lens snapshot; pure but for logging. */
internal fun CameraViewModel.camera2NightDecision(
    facade: CharacteristicsFacade,
): NightEngine {
    val probe = Camera2ExtensionProbe.probeNight(facade.snapshot)
    val input = ExtensionsEngineInput(
        mode = cameraModeMutable.value,
        camera2ProbeOk = probe.usable,
        failureCacheClear = !nightExtFailedRecently(),
    )
    val engine = ExtensionsEnginePolicy.select(input)
    Log.debug(
        "Camera2Night",
        "Camera2 night decision: engine=$engine probe=$probe " +
            "camera2Reason=${ExtensionsEnginePolicy.camera2ExclusionReason(input) ?: "none"}",
    )
    return engine
}

/**
 * Attempts the night shutter on the Camera2 extension session (gate default on). Returns
 * true when Camera2 took the shot (the caller must not also route it to
 * CameraX/custom); false means the existing routing decides, for a policy
 * reason or because no gated extension session is live. The caller has
 * already claimed the in-flight flag; every outcome here clears it (the
 * fallback paths re-claim it themselves).
 */
internal fun CameraViewModel.tryCaptureCamera2NightExtension(): Boolean {
    val session = activeCamera2ExtensionSession ?: return false
    val probe = session.currentProbe()
    val input = ExtensionsEngineInput(
        mode = cameraModeMutable.value,
        camera2ProbeOk = probe?.usable == true && session.hasJpegOutput,
        failureCacheClear = !nightExtFailedRecently(),
    )
    if (!ExtensionsEnginePolicy.usesCamera2Extension(input)) return false

    // Hide, never fake, the strength indicator on this path.
    extensionStrengthMutable.value = null
    session.setFlashSetting(
        when (flashModeMutable.value) {
            FlashMode.ON -> com.vayunmathur.camera.domain.FlashSetting.ON
            FlashMode.AUTO -> com.vayunmathur.camera.domain.FlashSetting.AUTO
            FlashMode.OFF -> com.vayunmathur.camera.domain.FlashSetting.OFF
        },
    )
    val mirrored = mirrorCaptures
    emitShutterFeedback()
    session.capture(
        mirrored = mirrored,
        callback = object : Camera2ExtensionSession.StillCallback {
            override fun onStillCaptured(still: CapturedStill) {
                viewModelScope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        runCatching { saveCamera2Still(still) }
                            .onFailure { Log.error("Camera2Night", "save failed", it) }
                            .getOrNull()
                    }
                    isCapturingMutable.value = false
                    if (uri != null) setLastCaptureUri(uri)
                }
            }

            override fun onStillFailed(message: String) {
                // Same failure store as CameraX bind/async failures: one
                // bad extender suppresses both engines for the 7-day TTL.
                Log.status("Camera2Night", "extension capture failed: $message (caching failure)")
                recordNightExtensionFailure()
                isCapturingMutable.value = false
            }
        },
    )
    return true
}

/**
 * Evaluates (and logs) the Camera2 night decision for the current lens
 * when the runtime gate is on; a no-op otherwise. Never routes a capture
 * itself — used by bind-time logging/diagnostics, mirroring
 * `evaluateCamera2SloMoDecision`.
 */
internal fun CameraViewModel.evaluateCamera2NightDecision() {
    try {
        val facing = selectedLensMutable.value?.facing
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
        camera2NightDecision(facade)
    } catch (e: IllegalStateException) {
        Log.status("Camera2Night", "Camera2 night decision failed; staying on existing routing", e)
    } catch (e: IllegalArgumentException) {
        Log.status("Camera2Night", "Camera2 night decision failed; staying on existing routing", e)
    }
}
