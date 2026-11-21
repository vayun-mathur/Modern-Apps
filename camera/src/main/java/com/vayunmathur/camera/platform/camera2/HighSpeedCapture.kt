package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraMetadata
import android.util.Range

/**
 * Camera2 migration Phase 5: slo-mo on a constrained high-speed session.
 *
 * Today SLOW_MO uses CameraX `HighSpeedVideoSessionConfig`
 * (`setSlowMotionEnabled(true)`, a pinned HFR range, `SLOMO_<ts>.mp4`, no
 * audio, saved through `saveVideoStaged`). The Camera2 equivalent is a
 * `ConstrainedHighSpeedCaptureSession` whose repeating `TEMPLATE_RECORD`
 * request is pinned to one exact FPS range and targets a MediaCodec encoder
 * surface (plus preview). This file is the pure half: it selects the
 * size/range from the facade snapshot and resolves the exact request and
 * encoder fields. No trial binds, no HAL iteration order leaks in.
 *
 * Slow-motion marking (the UI/container contract): CameraX writes no
 * proprietary slo-mo atom — the recorded MP4 *is* slow because its video
 * track timestamps/frame-rate play at normal speed while frames were
 * captured at HFR. The Camera2 encoder contract mirrors that with
 * `MediaFormat.KEY_CAPTURE_RATE = captureFps` and
 * `KEY_FRAME_RATE = playbackFps` (30): a conforming encoder stretches the
 * sample timestamps by capture/playback, so players need no extra
 * metadata. [HighSpeedSelection.stretchedPtsUs] is the same stretch, kept
 * pure so a muxer-side fallback can be tested without a codec. If a
 * device's encoder ignores `KEY_CAPTURE_RATE`, the file would be realtime
 * HFR, not slo-mo — that is a device-verification remainder, not something
 * a metadata flag can fix after the fact, and it keeps the gate off.
 *
 * Output decision: when the snapshot carries the constrained capability
 * plus a usable fixed range/size, the output is the encoder surface
 * ([HighSpeedOutput.ENCODER_SURFACE]). A stills-style YUV/JPEG burst was
 * rejected as the fallback: it has no container, no audio/timing contract
 * and cannot land through `saveVideoStaged` as an MP4, so presenting it as
 * slo-mo would be dishonest. Devices without a usable selection stay on
 * CameraX (`SloMoEnginePolicy`).
 */

/** Which output a high-speed session feeds. */
enum class HighSpeedOutput { ENCODER_SURFACE, NONE }

/** Encoder fields for one slo-mo recording (Phase-1 engine vocabulary). */
data class HighSpeedEncoderPlan(
    val width: Int,
    val height: Int,
    /** `MediaFormat.KEY_FRAME_RATE`: the rate the MP4 plays back at. */
    val frameRateFps: Int,
    /** `MediaFormat.KEY_CAPTURE_RATE`: the rate frames are captured at. */
    val captureRateFps: Int,
)

/** One selected slo-mo operating point. */
data class HighSpeedSelection(
    val size: FrameSize,
    /** Exact fixed range to pin (`lower == upper == captureFps`). */
    val fpsRange: Range<Int>,
    val captureFps: Int,
    val playbackFps: Int,
    val output: HighSpeedOutput,
) {
    /** How much slower than realtime playback is (4x at 120 -> 30). */
    val slowdownFactor: Float get() = captureFps.toFloat() / playbackFps.toFloat()

    val encoderPlan: HighSpeedEncoderPlan get() = HighSpeedEncoderPlan(
        width = size.width,
        height = size.height,
        frameRateFps = playbackFps,
        captureRateFps = captureFps,
    )

    /** Muxer-side timestamp stretch, if the encoder did not apply it. */
    fun stretchedPtsUs(realtimePtsUs: Long): Long =
        (realtimePtsUs.toDouble() * captureFps.toDouble() / playbackFps.toDouble()).toLong()
}

/**
 * Resolved `CaptureRequest` fields for the high-speed `TEMPLATE_RECORD`
 * repeating request. The FPS range is the whole point: it is exact and
 * fixed, so AE cannot wander to a lower rate mid-recording.
 */
data class ResolvedHighSpeedParams(
    /** `CONTROL_AE_TARGET_FPS_RANGE`; always `Range(fps, fps)`. */
    val aeTargetFpsRange: Range<Int>,
    val captureFps: Int,
    val playbackFps: Int,
    /** `CONTROL_AE_MODE` (`CameraMetadata.CONTROL_AE_MODE_*`). */
    val aeMode: Int,
    /** `CONTROL_AF_MODE` (`CameraMetadata.CONTROL_AF_MODE_*`). */
    val afMode: Int,
    /** `FLASH_MODE` (`CameraMetadata.FLASH_MODE_*`); slo-mo never flashes. */
    val flashMode: Int,
)

object HighSpeedPlanner {

    /** Playback rate of the slo-mo MP4, matching the CameraX contract. */
    const val PLAYBACK_FPS: Int = 30

    /** Minimum capture rate that counts as true slo-mo (mirrors CameraX HFR). */
    const val MIN_CAPTURE_FPS: Int = 60

    /**
     * Every usable (size, fixed range) candidate in the snapshot. A range
     * is usable only when it is already fixed (lower == upper) and at or
     * above [MIN_CAPTURE_FPS]: pinning a non-advertised fixed range risks
     * a HAL configure failure, so variable ranges are not normalised here.
     */
    fun candidates(snapshot: LensCharacteristicsSnapshot): List<HighSpeedSelection> {
        if (!snapshot.supportsConstrainedHighSpeedVideo) return emptyList()
        return snapshot.highSpeedVideoSizes.flatMap { size ->
            val frameSize = FrameSize.from(size)
            snapshot.highSpeedFpsRangesBySize[size].orEmpty()
                .filter { it.lower == it.upper && it.upper >= MIN_CAPTURE_FPS }
                .map { range ->
                    HighSpeedSelection(
                        size = frameSize,
                        fpsRange = Range(range.upper, range.upper),
                        captureFps = range.upper,
                        playbackFps = PLAYBACK_FPS,
                        output = HighSpeedOutput.ENCODER_SURFACE,
                    )
                }
        }
    }

    /**
     * The operating point to record at: highest capture fps first (240
     * over 120, as the CameraX ladder tries ranges highest-first), then
     * largest area, then width/height for determinism. Null when the lens
     * offers no honest slo-mo point — the caller stays on CameraX.
     */
    fun select(snapshot: LensCharacteristicsSnapshot): HighSpeedSelection? =
        candidates(snapshot).maxWithOrNull(
            compareBy({ it.captureFps }, { it.size.area }, { it.size.width }, { it.size.height }),
        )

    /** Resolves the pinned `TEMPLATE_RECORD` fields for [selection]. */
    fun resolveRequest(selection: HighSpeedSelection): ResolvedHighSpeedParams =
        ResolvedHighSpeedParams(
            aeTargetFpsRange = Range(selection.captureFps, selection.captureFps),
            captureFps = selection.captureFps,
            playbackFps = selection.playbackFps,
            aeMode = CameraMetadata.CONTROL_AE_MODE_ON,
            afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            flashMode = CameraMetadata.FLASH_MODE_OFF,
        )
}
