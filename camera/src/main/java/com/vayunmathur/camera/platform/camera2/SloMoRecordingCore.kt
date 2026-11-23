package com.vayunmathur.camera.platform.camera2

/**
 * Pure structural core for coupling the constrained high-speed session to
 * a recording engine (SloMoEnginePolicy pieces 1, 3 and 5). No Android
 * types: the ordering rules are unit-testable without a codec or HAL.
 *
 * Nothing here routes a live recording. [SloMoRecordingReadiness] keeps
 * `recordingEngineReady` false until every documented piece closes and a
 * device run verifies the output (piece 6).
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

/**
 * Readiness of SloMoEnginePolicy's seven documented pieces after the
 * structural work of 2026-11-22. Only pieces closed in code may read
 * true; [recordingEngineReady] is their conjunction, so it stays false
 * (and slo-mo stays on CameraX) until the rest land *and* a device run
 * verifies stretched timestamps / slow duration (piece 6).
 */
object SloMoRecordingReadiness {
    /** 1. Codec-first split; surface exposed before camera open. */
    const val ENCODER_SURFACE_HANDOFF_READY: Boolean = true

    /** 2. Codec/muxer/drain core shared by engine and high-speed session. */
    const val SHARED_CODEC_CORE_READY: Boolean = false

    /** 3. Stop boundary on the high-speed session: stop repeating, then EOS, no caller-surface close. */
    const val SESSION_RECORDING_BOUNDARY_READY: Boolean = true

    /** 4. Camera2 slo-mo preview composable/bridge. */
    const val SLO_MO_PREVIEW_READY: Boolean = false

    /** 5. No-audio muxer mode (muxer starts on the video track alone). */
    const val NO_AUDIO_MUXER_READY: Boolean = true

    /** 6. Timestamp stretching proof + read-back frame-rate/duration checks. */
    const val TIMESTAMP_VERIFICATION_READY: Boolean = false

    /** 7. Save-path glue (engine + session together, staged save, CameraX fallback latch). */
    const val SAVE_PATH_GLUE_READY: Boolean = false

    /** True only when every piece above is ready; feeds SloMoEngineInput.recordingEngineReady. */
    val recordingEngineReady: Boolean
        get() = ENCODER_SURFACE_HANDOFF_READY && SHARED_CODEC_CORE_READY &&
            SESSION_RECORDING_BOUNDARY_READY && SLO_MO_PREVIEW_READY &&
            NO_AUDIO_MUXER_READY && TIMESTAMP_VERIFICATION_READY && SAVE_PATH_GLUE_READY
}
