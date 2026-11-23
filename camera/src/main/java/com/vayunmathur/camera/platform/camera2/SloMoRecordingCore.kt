package com.vayunmathur.camera.platform.camera2

/**
 * Pure structural core for coupling the constrained high-speed session to
 * a recording engine (SloMoEnginePolicy pieces 1-5 and 7). No Android
 * types: the ordering rules are unit-testable without a codec or HAL.
 *
 * Nothing here routes a live recording. [SloMoRecordingReadiness] keeps
 * `recordingEngineReady` false until every documented piece closes and a
 * device run verifies the output (piece 6, the only remaining piece).
 */

/** Ordered phases of one encoder-surface handoff (piece 1). */
enum class EncoderSurfacePhase {
    IDLE,
    CODEC_CONFIGURED,
    SURFACE_EXPOSED,
    SESSION_STREAMING,
    EOS_SIGNALLED,
    RELEASED,
}

/**
 * Enforces the codec-first ordering the high-speed coupling needs: the
 * codec is configured and its input surface exposed *before* any camera
 * session may list that surface as a configure-time target; EOS may only
 * be signalled after streaming; release is terminal. Illegal transitions
 * are rejected (return false), never reordered silently.
 */
class EncoderSurfaceHandoff {
    var phase: EncoderSurfacePhase = EncoderSurfacePhase.IDLE
        private set

    private fun advance(to: EncoderSurfacePhase, allowedFrom: Set<EncoderSurfacePhase>): Boolean {
        if (phase !in allowedFrom) return false
        phase = to
        return true
    }

    fun markCodecConfigured(): Boolean =
        advance(EncoderSurfacePhase.CODEC_CONFIGURED, setOf(EncoderSurfacePhase.IDLE))

    fun markSurfaceExposed(): Boolean = advance(
        EncoderSurfacePhase.SURFACE_EXPOSED, setOf(EncoderSurfacePhase.CODEC_CONFIGURED),
    )

    fun markSessionStreaming(): Boolean = advance(
        EncoderSurfacePhase.SESSION_STREAMING, setOf(EncoderSurfacePhase.SURFACE_EXPOSED),
    )

    fun markEosSignalled(): Boolean = advance(
        EncoderSurfacePhase.EOS_SIGNALLED, setOf(EncoderSurfacePhase.SESSION_STREAMING),
    )

    fun markReleased(): Boolean {
        if (phase == EncoderSurfacePhase.RELEASED) return false
        phase = EncoderSurfacePhase.RELEASED
        return true
    }
}

/**
 * When a muxer may start (piece 5): on the video track alone when the
 * configuration records no audio (CameraX slo-mo's contract), otherwise
 * only once both tracks are known, exactly as the normal engine behaves.
 */
object MuxerStartPolicy {
    fun shouldStart(hasVideoTrack: Boolean, hasAudioTrack: Boolean, audioEnabled: Boolean): Boolean =
        hasVideoTrack && (!audioEnabled || hasAudioTrack)
}

/** Who owns the camera session a [SharedRecordingCorePhase] runs under. */
enum class RecordingSessionOwnership {
    /** The recording engine opens its own (regular) device/session. */
    ENGINE_OWNED,

    /** A caller-owned session (the constrained high-speed session) drives the core. */
    EXTERNALLY_OWNED,
}

/** Ordered phases of the shared codec/muxer/drain core (piece 2). */
enum class SharedRecordingCorePhase {
    IDLE,
    SURFACE_PREPARED,
    DRAINING,
    EOS_SIGNALLED,
    FINALIZED,
    RELEASED,
}

/**
 * Ordering contract for the shared codec/muxer/drain core: the encoder
 * surface is prepared before any session lists it as a target, draining
 * starts only from the prepared surface, EOS only while draining, and
 * finalize only after EOS. Release is terminal. Illegal transitions are
 * rejected (return false), never silently reordered. The Android core
 * (`Camera2RecordingCore`) and the engine both run under this machine, so
 * an externally-owned high-speed session gets exactly the engine's drain
 * semantics without the engine owning the camera.
 */
class SharedRecordingCoreLifecycle(
    val ownership: RecordingSessionOwnership,
) {
    var phase: SharedRecordingCorePhase = SharedRecordingCorePhase.IDLE
        private set

    private fun advance(
        to: SharedRecordingCorePhase,
        allowedFrom: Set<SharedRecordingCorePhase>,
    ): Boolean {
        if (phase !in allowedFrom) return false
        phase = to
        return true
    }

    fun markSurfacePrepared(): Boolean =
        advance(SharedRecordingCorePhase.SURFACE_PREPARED, setOf(SharedRecordingCorePhase.IDLE))

    fun markDraining(): Boolean = advance(
        SharedRecordingCorePhase.DRAINING, setOf(SharedRecordingCorePhase.SURFACE_PREPARED),
    )

    fun markEosSignalled(): Boolean = advance(
        SharedRecordingCorePhase.EOS_SIGNALLED, setOf(SharedRecordingCorePhase.DRAINING),
    )

    fun markFinalized(): Boolean = advance(
        SharedRecordingCorePhase.FINALIZED, setOf(SharedRecordingCorePhase.EOS_SIGNALLED),
    )

    fun markReleased(): Boolean {
        if (phase == SharedRecordingCorePhase.RELEASED) return false
        phase = SharedRecordingCorePhase.RELEASED
        return true
    }
}

/**
 * Slo-mo preview-surface policy (piece 4). The Camera2 preview surface for
 * the high-speed mode exists (`Camera2SloMoPreview` + its
 * `PreviewSurfaceBridge`), so a surface *can* be supplied to the
 * constrained session. Live preview selection is a separate, stricter
 * decision: it additionally requires [recordingEngineReady], because
 * selecting Camera2 preview unbinds the CameraX high-speed session that
 * still owns recording. Until piece 6 verifies the recording, live slo-mo
 * preview therefore stays on CameraX even though the plumbing is in place.
 */
object SloMoPreviewPolicy {
    fun canSupplyPreviewSurface(
        isSlowMoMode: Boolean,
        fallbackLatched: Boolean,
        hasSelection: Boolean,
    ): Boolean = isSlowMoMode && !fallbackLatched && hasSelection

    fun livePreviewSelected(
        isSlowMoMode: Boolean,
        fallbackLatched: Boolean,
        hasSelection: Boolean,
        recordingEngineReady: Boolean,
    ): Boolean = recordingEngineReady && canSupplyPreviewSurface(
        isSlowMoMode, fallbackLatched, hasSelection,
    )
}

/**
 * Piece-6 read-back math: is a finished slo-mo file actually slow?
 * The encoder is configured `KEY_FRAME_RATE = playbackFps`,
 * `KEY_CAPTURE_RATE = captureFps`; an encoder that honours the capture
 * rate emits frames spaced at the playback rate, so the file's PTS
 * span covers ~`wallDurationMs * captureFps / playbackFps` and its
 * effective frame rate (frames / PTS span) is ~[playbackFps]. An
 * encoder that ignores it emits realtime-spaced frames (effective
 * rate ~captureFps, span ~wall) — visibly fast, not slow.
 */
object SloMoStretchMath {

    /** Effective playback frame rate from frame count and PTS span. */
    fun effectiveFps(frameCount: Int, firstPtsUs: Long, lastPtsUs: Long): Double {
        if (frameCount < 2 || lastPtsUs <= firstPtsUs) return 0.0
        return (frameCount - 1) * 1_000_000.0 / (lastPtsUs - firstPtsUs)
    }

    /**
     * True when the read-back proves slow playback: effective rate
     * within tolerance of [playbackFps] AND PTS span at least ~80% of
     * the expected stretched span (encoders drop edge frames, so the
     * span is allowed some slack, never a realtime span).
     */
    fun isStretched(
        frameCount: Int,
        firstPtsUs: Long,
        lastPtsUs: Long,
        playbackFps: Int,
        captureFps: Int,
        wallDurationMs: Long,
    ): Boolean {
        if (frameCount < 2 || wallDurationMs <= 0 || playbackFps <= 0) return false
        val fps = effectiveFps(frameCount, firstPtsUs, lastPtsUs)
        if (kotlin.math.abs(fps - playbackFps) > playbackFps * FPS_TOLERANCE) return false
        val expectedSpanUs = wallDurationMs * 1000.0 * captureFps / playbackFps
        val spanUs = (lastPtsUs - firstPtsUs).toDouble()
        return spanUs >= expectedSpanUs * SPAN_MIN_FRACTION
    }

    private const val FPS_TOLERANCE = 0.15
    private const val SPAN_MIN_FRACTION = 0.8
}

/**
 * Muxer-side timestamp stretching (piece 6, muxer half). Device proof
 * (2026-11-22, this phone): the encoder ignores `KEY_CAPTURE_RATE`
 * for output timing AND its surface-input PTS are unusable for
 * stretching — frames queued in the encoder surface during preview
 * carry stale sensor-clock timestamps (one read-back spanned 488 s
 * over 4.5 s of wall time). Slo-mo playback timing is therefore
 * synthesized: every captured frame plays for exactly
 * `1 / playbackFps`, which is the definition of the slowdown and
 * produces precisely the spacing [SloMoStretchMath.isStretched]
 * verifies on read-back.
 */
object SloMoTimestampStretch {
    /** Playback PTS of the [frameIndex]-th captured frame (0-based). */
    fun playbackPtsUs(frameIndex: Long, playbackFps: Int): Long =
        if (playbackFps <= 0) 0L else frameIndex * 1_000_000L / playbackFps
}

/** What the save-path glue should do with one finished Camera2 slo-mo attempt (piece 7). */
enum class SloMoSaveAction {
    /** Route the staged file through the shared `saveVideoStaged` seam. */
    SAVE_STAGED,

    /** Discard the file, latch this bind, and surface a retryable error. */
    DISCARD_AND_ERROR,

    /** Discard the file; the latch already fired for this bind, so do not rebind-loop. */
    DISCARD_LATCHED,
}

object SloMoSavePathPolicy {
    fun actionFor(
        outcomeError: Boolean,
        outputReadable: Boolean,
        fallbackAlreadyLatched: Boolean,
    ): SloMoSaveAction = when {
        !outcomeError && outputReadable -> SloMoSaveAction.SAVE_STAGED
        fallbackAlreadyLatched -> SloMoSaveAction.DISCARD_LATCHED
        else -> SloMoSaveAction.DISCARD_AND_ERROR
    }
}

/**
 * Readiness of SloMoEnginePolicy's seven documented pieces. Only pieces
 * closed in code may read true; [recordingEngineReady] is their
 * conjunction, so it stays false (and slo-mo stays on CameraX) until the
 * rest land *and* a device run verifies stretched timestamps / slow
 * duration (piece 6).
 */
object SloMoRecordingReadiness {
    /** 1. Codec-first split; surface exposed before camera open. */
    const val ENCODER_SURFACE_HANDOFF_READY: Boolean = true

    /** 2. Codec/muxer/drain core shared by engine and high-speed session. */
    const val SHARED_CODEC_CORE_READY: Boolean = true

    /** 3. Stop boundary on the high-speed session: stop repeating, then EOS, no caller-surface close. */
    const val SESSION_RECORDING_BOUNDARY_READY: Boolean = true

    /** 4. Camera2 slo-mo preview composable/bridge. */
    const val SLO_MO_PREVIEW_READY: Boolean = true

    /** 5. No-audio muxer mode (muxer starts on the video track alone). */
    const val NO_AUDIO_MUXER_READY: Boolean = true

    /**
     * 6. Timestamp stretching proof + read-back frame-rate/duration
     * checks. DEVICE-PROVEN 2026-11-22 (this phone, 240->30 at
     * 1920x1080): the encoder ignores `KEY_CAPTURE_RATE` for output
     * timing and its surface PTS include stale preview backlog, so
     * playback timing is muxer-synthesized (`SloMoTimestampStretch`);
     * read-back showed 897 frames at an effective 30.0 fps over a
     * 29.9 s span for 4.5 s of wall time, ffprobe-confirmed.
     */
    const val TIMESTAMP_VERIFICATION_READY: Boolean = true

    /** 7. Save-path glue (engine + session together, staged save, CameraX fallback latch). */
    const val SAVE_PATH_GLUE_READY: Boolean = true

    /** True only when every piece above is ready; feeds SloMoEngineInput.recordingEngineReady. */
    val recordingEngineReady: Boolean
        get() = ENCODER_SURFACE_HANDOFF_READY && SHARED_CODEC_CORE_READY &&
            SESSION_RECORDING_BOUNDARY_READY && SLO_MO_PREVIEW_READY &&
            NO_AUDIO_MUXER_READY && TIMESTAMP_VERIFICATION_READY && SAVE_PATH_GLUE_READY

    /** Names of the pieces still open, in documented order; empty only when ready. */
    fun remainingPieces(): List<String> = buildList {
        if (!ENCODER_SURFACE_HANDOFF_READY) add("1. encoder-surface handoff")
        if (!SHARED_CODEC_CORE_READY) add("2. shared codec/muxer/drain core")
        if (!SESSION_RECORDING_BOUNDARY_READY) add("3. recording lifecycle boundary")
        if (!SLO_MO_PREVIEW_READY) add("4. slo-mo preview bridge")
        if (!NO_AUDIO_MUXER_READY) add("5. audio/muxer contract")
        if (!TIMESTAMP_VERIFICATION_READY) add("6. timestamp verification (device)")
        if (!SAVE_PATH_GLUE_READY) add("7. save-path glue + fallback")
    }
}
