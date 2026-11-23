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
        sloMoGateEnabled: Boolean,
        isSlowMoMode: Boolean,
        fallbackLatched: Boolean,
        hasSelection: Boolean,
    ): Boolean = sloMoGateEnabled && isSlowMoMode && !fallbackLatched && hasSelection

    fun livePreviewSelected(
        sloMoGateEnabled: Boolean,
        isSlowMoMode: Boolean,
        fallbackLatched: Boolean,
        hasSelection: Boolean,
        recordingEngineReady: Boolean,
    ): Boolean = recordingEngineReady && canSupplyPreviewSurface(
        sloMoGateEnabled, isSlowMoMode, fallbackLatched, hasSelection,
    )
}

/** What the save-path glue should do with one finished Camera2 slo-mo attempt (piece 7). */
enum class SloMoSaveAction {
    /** Route the staged file through the shared `saveVideoStaged` seam. */
    SAVE_STAGED,

    /** Discard the file, latch this bind, and rebind the CameraX HFR ladder. */
    DISCARD_AND_REBIND_CAMERAX,

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
        else -> SloMoSaveAction.DISCARD_AND_REBIND_CAMERAX
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

    /** 6. Timestamp stretching proof + read-back frame-rate/duration checks. */
    const val TIMESTAMP_VERIFICATION_READY: Boolean = false

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
