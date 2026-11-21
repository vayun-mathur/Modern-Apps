package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraExtensionCharacteristics
import android.util.Range

/**
 * Camera2 migration Phase 6: NIGHT availability probe over the Phase-0
 * facade snapshot, mirroring the CameraX probe's contract in
 * `CameraNightMode.kt` (`isNightExtensionAvailable`):
 *
 * 1. Per-lens availability — CameraX asks
 *    `ExtensionsManager.isExtensionAvailable(selector, NIGHT)` for the
 *    selected lens; here the snapshot's `supportedExtensions` for *this*
 *    lens either contains `EXTENSION_NIGHT` or it does not. No other lens's
 *    support leaks in, and no trial bind is attempted.
 * 2. Session-config support — CameraX asks
 *    `cameraInfo.isSessionConfigSupported(Preview + ImageCapture NIGHT)`;
 *    the Camera2 equivalent of that exact config is an
 *    `ExtensionSessionConfiguration(EXTENSION_NIGHT, preview + still)`
 *    whose output sizes the extension advertises. Support therefore
 *    requires at least one advertised preview (`SurfaceTexture`) size
 *    *and* at least one advertised JPEG still size for NIGHT. A mode that
 *    is listed but advertises no usable pair is *not* supported — the
 *    session would fail configure, which is precisely what the CameraX
 *    query exists to avoid.
 *
 * The estimated capture latency rides along for expectations/logging; it
 * never gates support (the HAL may legitimately report no estimate).
 *
 * Failure-cache contract (unchanged, not forked): this probe does *not*
 * consult the persisted failure cache. Exactly as today, the caller checks
 * `nightExtFailedRecently()` first (7-day TTL, `night_ext_failed_at` in
 * `CameraNightMode.kt`) and only probes when it is clear; a real bind or
 * async failure on either engine records into that same store via
 * `recordNightExtensionFailure()`. See [ExtensionsEnginePolicy].
 *
 * Detection contract: CameraX's `getNightModeIndicator()` has no Camera2
 * equivalent. On the Camera2 path night *engagement* is therefore driven
 * by the existing luma hysteresis (`PhotoAnalyzer` average-Y fed into
 * `CameraViewModel.onLuminance`, engage < 40 / disengage > 55 over 4
 * frames), never by an indicator. The indicator remains observed only on
 * CameraX-bound cameras; a Camera2 session must not observe it (it would
 * report UNKNOWN/NOT_RECOMMENDED and fight the hysteresis — the same
 * reason the CameraX extension camera is not observed today).
 */

/** Outcome of probing one extension mode on one lens snapshot. */
data class ExtensionProbeResult(
    val mode: Int,
    /** The mode appears in this lens's `supportedExtensions`. */
    val available: Boolean,
    /** A preview+still `ExtensionSessionConfiguration` can be built. */
    val sessionConfigSupported: Boolean,
    /** Deterministically selected preview size, or null when unsupported. */
    val previewSize: FrameSize?,
    /** Deterministically selected JPEG still size, or null when unsupported. */
    val stillSize: FrameSize?,
    /** Estimated capture latency (millis) for [stillSize], when reported. */
    val estimatedCaptureLatencyRangeMillis: Range<Long>?,
    /** Why the mode is unusable, or null when it is usable. */
    val rejectionReason: String?,
) {
    /** True only when both contract halves pass. */
    val usable: Boolean get() = rejectionReason == null
}

object Camera2ExtensionProbe {

    /** The only extension mode Phase 6 routes. */
    const val NIGHT_MODE: Int = CameraExtensionCharacteristics.EXTENSION_NIGHT

    /** Probes [mode] on [snapshot]; pure and deterministic. */
    fun probe(snapshot: LensCharacteristicsSnapshot, mode: Int): ExtensionProbeResult {
        if (!snapshot.hasExtension(mode)) {
            return ExtensionProbeResult(
                mode = mode,
                available = false,
                sessionConfigSupported = false,
                previewSize = null,
                stillSize = null,
                estimatedCaptureLatencyRangeMillis = null,
                rejectionReason = "extension $mode not in supportedExtensions for camera ${snapshot.cameraId}",
            )
        }
        val support = snapshot.extensionSupportFor(mode)
        val previewSizes = support?.previewSizes.orEmpty().map { FrameSize.from(it) }.distinct()
        val jpegSizes = support?.jpegSizes.orEmpty().map { FrameSize.from(it) }.distinct()
        if (previewSizes.isEmpty() || jpegSizes.isEmpty()) {
            return ExtensionProbeResult(
                mode = mode,
                available = true,
                sessionConfigSupported = false,
                previewSize = null,
                stillSize = null,
                estimatedCaptureLatencyRangeMillis = null,
                rejectionReason = "extension $mode advertises no preview+JPEG size pair " +
                    "(preview=${previewSizes.size}, jpeg=${jpegSizes.size})",
            )
        }
        // Still: largest advertised JPEG (extension stills are the saved
        // image; CameraX's ImageCapture likewise takes the max still size).
        val still = jpegSizes.maxWithOrNull(
            compareBy({ it.area }, { it.width }, { it.height }),
        )
        // Preview: largest advertised preview size; the session re-selects
        // against the actual surface size at open time.
        val preview = previewSizes.maxWithOrNull(
            compareBy({ it.area }, { it.width }, { it.height }),
        )
        return ExtensionProbeResult(
            mode = mode,
            available = true,
            sessionConfigSupported = true,
            previewSize = preview,
            stillSize = still,
            estimatedCaptureLatencyRangeMillis = still?.let { selected ->
                support?.estimatedLatencyFor(selected.toSize())
            },
            rejectionReason = null,
        )
    }

    /** Probes `EXTENSION_NIGHT` on [snapshot]. */
    fun probeNight(snapshot: LensCharacteristicsSnapshot): ExtensionProbeResult =
        probe(snapshot, NIGHT_MODE)
}
