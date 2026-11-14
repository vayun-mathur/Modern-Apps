package com.vayunmathur.cast.platform

import com.vayunmathur.cast.domain.CastDevice

/**
 * Where the session is, from the point of view of a screen.
 *
 * Flatter than `ClientPhase`, with one exception that has to survive: [AwaitingCode] is a state the
 * user must act on, so it cannot be folded into "connecting".
 */
enum class CastConnection { Disconnected, Connecting, AwaitingCode, Connected, Failed }

/**
 * How far mirroring itself has got, which is a separate axis from [CastConnection].
 *
 * A paired TV is not a running mirror: the stream has to be configured and the encoders have to start,
 * and either can fail while the control channel stays perfectly healthy.
 */
enum class MirrorPhase {
    /** Nothing is being sent. The TV may still be paired and waiting. */
    Idle,

    /** `STREAM_CONFIG` is on the wire, or the encoders are starting. */
    Negotiating,

    /** Frames are going out. */
    Mirroring,

    /** Mirroring stopped or never started, and [CastUiState.failure] says why. */
    Failed,
}

/**
 * Everything the Cast screen draws.
 *
 * Every field is defaulted so a `@Preview` can build one from the parts it cares about - which is what
 * the store-listing images are rendered from (see `src/screenshotTest`). It lives beside the ViewModel
 * so the dependency runs one way: `ui` depends on this, and the ViewModel implements [CastActions].
 */
data class CastUiState(
    val devices: List<CastDevice> = emptyList(),
    val isScanning: Boolean = false,
    /**
     * Android 16+ blocked the mDNS browse. Distinct from an empty list, because the fix is a permission
     * rather than turning the TV on.
     */
    val localNetworkBlocked: Boolean = false,
    val connectedDevice: CastDevice? = null,
    val connection: CastConnection = CastConnection.Disconnected,
    val mirrorPhase: MirrorPhase = MirrorPhase.Idle,
    /** How many pair attempts are left, shown while [connection] is [CastConnection.AwaitingCode]. */
    val pairAttemptsLeft: Int = 0,
    /** The TV threw its code away after three wrong tries and is showing a new one. */
    val pairCodeChanged: Boolean = false,
    /** Video could not be encoded, so only audio is going out. */
    val videoDegraded: Boolean = false,
    /** Audio could not be captured or encoded, so only the picture is going out. */
    val audioDegraded: Boolean = false,
    /** Why the TV refused, or why mirroring stopped, ready to show a user. */
    val failure: String? = null,
) {
    val isMirroring: Boolean get() = mirrorPhase == MirrorPhase.Mirroring

    /** Mirroring can be offered only once there is a paired TV to send it to. */
    val canMirror: Boolean get() = connection == CastConnection.Connected
}

/** Callbacks for scanning, connecting, pairing, and mirroring from the cast screen. */
interface CastActions {
    fun startScan() {}
    fun stopScan() {}
    fun connect(device: CastDevice) {}
    fun disconnect() {}
    fun submitPairCode(code: String) {}
    fun startMirroring() {}
    fun stopMirroring() {}
    fun openLocalNetworkSettings() {}

        /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: CastActions = object : CastActions {}
    }
}
