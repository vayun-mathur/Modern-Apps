package com.vayunmathur.camera.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.data.loadSettings
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Startup sequencing for [CameraViewModel]: the synchronous startup-lens
 * seed plus the background enrichment launches, moved verbatim from the
 * ViewModel's `init` block as extensions (the gate state and flows
 * themselves stay on the ViewModel). Behaviour is unchanged.
 */

/** Records the first Camera2 bind request (from the preview composable). */
fun CameraViewModel.noteCamera2BindRequested() {
    if (startupOrder.recordBindRequested()) {
        Log.dev("CameraStartup", "camera2 bind requested (probes may still be running)")
    }
}

/**
 * Runs the ViewModel's startup sequence: seed first, persisted settings
 * second, and every CameraX-dependent probe in the background so the
 * Camera2 preview never waits on them.
 */
internal fun CameraViewModel.runStartupSequence() {
    // Fast path first: seed the persisted lens synchronously so the
    // Camera2 preview gate holds on frame one (see seedStartupLens).
    // Nothing below may gate that bind.
    seedStartupLens()
    loadSettings()
    panoramaEngine.onSweepComplete = { finishPanoramaSweep() }
    viewModelScope.launch {
        lastCaptureUriMutable.collect { uri -> _galleryThumbnail.value = loadThumbnail(uri) }
    }
    // Background enrichment AFTER first frame: CameraManager lens
    // enumeration (per-lens Characteristics reads) refines the seeded
    // placeholder. The Camera2 preview never waits on this: it opens
    // via CameraManager + facing directly.
    viewModelScope.launch(Dispatchers.Default) {
        try {
            ensureLensesEnumerated()
        } catch (e: IllegalStateException) {
            Log.status("CameraStartup", "background lens enrichment failed", e)
        } catch (e: IllegalArgumentException) {
            Log.status("CameraStartup", "background lens enrichment failed", e)
        }
        if (startupOrder.recordProbeCompleted()) {
            Log.dev(
                "CameraStartup",
                "background probes complete; " +
                    "bindRequestedBeforeProbe=${startupOrder.bindRequestedBeforeProbeCompleted()}",
            )
        }
    }
    // Probe Slo-Mo capability once (back camera only). UI hides Slo-Mo if unsupported.
    // Deferred off the startup path — irrelevant to the PHOTO first frame.
    viewModelScope.launch {
        sloMoSupportedMutable.value = probeSloMoSupport()
    }
    // Cache the MediaCodecList AVIF-encoder probe once, off the capture
    // path: StillEncoder only reads the verdict at shot time.
    viewModelScope.launch(Dispatchers.Default) {
        AvifCodecProbe.warmUp()
    }
    // DEBUG: Night mode resolution question – extension uses default resolution vs max-res,
    // may be lower so can't take full quality?
    startDebugLogging()
}
