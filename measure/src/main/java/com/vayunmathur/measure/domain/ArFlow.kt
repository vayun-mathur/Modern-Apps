package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.TrackingQuality

/** Why AR cannot run at all on this device/session, in user terms. */
enum class ArUnavailableReason {
    NativeEngineMissing,
    CameraPermissionDenied,
    CameraTimestampMismatch,
    MotionSensorsMissing,
}

/**
 * The AR measuring flow as one typed state, replacing the old implicit
 * mess of a quality enum plus a hasPlane flag plus null checks in the
 * page. The UI renders exactly one of these; placement is only possible
 * from [Ready], which is only reached with solid tracking and a sane
 * hit, so "tap does nothing" states are unrepresentable.
 */
sealed interface ArFlowState {
    /** AR flow state when measuring cannot run at all, carrying the user-facing [reason]. */
    data class Unavailable(val reason: ArUnavailableReason) : ArFlowState

    /** Engine/session not created yet (or camera not yet running). */
    data object NoSession : ArFlowState

    /**
     * Tracking is not solid yet. [timedOut] flips after
     * [ArFlow.INITIALISING_TIMEOUT_MS] so the UI can offer the manual
     * fallback instead of coaching forever.
     */
    data class Scanning(val timedOut: Boolean = false) : ArFlowState

    /** Tracking is solid but the centre ray currently hits nothing sane. */
    data object Searching : ArFlowState

    /** Solid tracking and a selected hit under the reticle. */
    data class Ready(val hit: HitCandidate) : ArFlowState

    /** Tracking was solid and is now lost; anchors are already cleared. */
    data class TrackingLost(val timedOut: Boolean = false) : ArFlowState
}

/** Pure derivation of the AR flow state and its scanning/lost timeouts from tracking quality and hit inputs. */
object ArFlow {
    const val INITIALISING_TIMEOUT_MS = 25_000L
    const val LOST_TIMEOUT_MS = 15_000L

    /**
     * Derive the flow state. Pure, so the whole machine is unit-testable
     * with fake quality/hit inputs and fake clocks.
     *
     * Anchors may only be placed when this returns [ArFlowState.Ready]:
     * the old flow allowed Limited tracking (scale confidence as low as
     * 0.3) to place points, which baked a guess at the metre into every
     * saved distance.
     */
    fun derive(
        unavailable: ArUnavailableReason?,
        hasSession: Boolean,
        quality: TrackingQuality,
        reticleHit: HitCandidate?,
        scanningSinceMs: Long?,
        lostSinceMs: Long?,
        nowMs: Long,
    ): ArFlowState {
        if (unavailable != null) return ArFlowState.Unavailable(unavailable)
        if (!hasSession) return ArFlowState.NoSession
        return when (quality) {
            TrackingQuality.Initialising -> ArFlowState.Scanning(
                timedOut = scanningSinceMs != null &&
                    nowMs - scanningSinceMs >= INITIALISING_TIMEOUT_MS,
            )
            TrackingQuality.Limited -> ArFlowState.Scanning(
                timedOut = scanningSinceMs != null &&
                    nowMs - scanningSinceMs >= INITIALISING_TIMEOUT_MS,
            )
            TrackingQuality.Lost -> ArFlowState.TrackingLost(
                timedOut = lostSinceMs != null &&
                    nowMs - lostSinceMs >= LOST_TIMEOUT_MS,
            )
            TrackingQuality.Good ->
                if (reticleHit != null) ArFlowState.Ready(reticleHit)
                else ArFlowState.Searching
        }
    }

    /** True in states where the "move your phone to scan" coach shows. */
    fun isScanning(state: ArFlowState): Boolean = state is ArFlowState.Scanning

    fun canPlace(state: ArFlowState): Boolean = state is ArFlowState.Ready
}
