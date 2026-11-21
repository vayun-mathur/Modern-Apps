package com.vayunmathur.camera.platform.camera2

/**
 * Camera2 migration Phase 0: pure state machine for a Camera2 capture session.
 * No `CameraDevice`/`CameraCaptureSession` calls live here — this phase is the
 * transition contract the later phases bind real callbacks into.
 *
 * Teardown token: [generation] is a monotonically increasing counter bumped by
 * every [beginTeardown]. Async callbacks capture the generation at scheduling
 * time and hand it back to [dispatch]/[isCurrent]; a callback from a previous
 * generation is dropped instead of acting on a session that no longer exists.
 * This is the same pattern the night-capture lifecycle fix used: bump first,
 * close second, let stale completions observe the mismatch and no-op.
 */
enum class SessionState {
    CLOSED,
    OPENING,
    OPEN,
    CONFIGURING,
    STREAMING,
    CLOSING,
    ERROR,
}

/** Inputs that can move the machine. Async callbacks carry a generation; see [SessionStateMachine.dispatch]. */
enum class SessionEvent {
    OPEN_REQUESTED,
    DEVICE_OPENED,
    CONFIGURE_REQUESTED,
    SESSION_CONFIGURED,
    CONFIGURE_FAILED,
    CLOSE_REQUESTED,
    DEVICE_CLOSED,
    ERROR_OCCURRED,
}

/** Outcome of a [SessionStateMachine.transition] / [SessionStateMachine.dispatch] call. */
sealed interface TransitionResult {
    data class Applied(val from: SessionState, val to: SessionState) : TransitionResult
    data class Rejected(val state: SessionState, val event: SessionEvent) : TransitionResult

    /** The callback's generation is no longer current; the event was dropped untouched. */
    data class StaleGeneration(val expected: Long, val actual: Long) : TransitionResult
}

class SessionStateMachine {

    var state: SessionState = SessionState.CLOSED
        private set

    /** Monotonic teardown token. Only ever increases. */
    var generation: Long = 0L
        private set

    fun isCurrent(candidateGeneration: Long): Boolean = candidateGeneration == generation

    /** Applies [event] if it is legal from the current state; otherwise rejects without mutating. */
    fun transition(event: SessionEvent): TransitionResult {
        val from = state
        val to = legalTarget(from, event) ?: return TransitionResult.Rejected(from, event)
        state = to
        return TransitionResult.Applied(from, to)
    }

    /**
     * Generation-guarded entry point for async callbacks: a stale generation is
     * dropped ([TransitionResult.StaleGeneration]) before legality is checked.
     */
    fun dispatch(callbackGeneration: Long, event: SessionEvent): TransitionResult {
        if (!isCurrent(callbackGeneration)) {
            return TransitionResult.StaleGeneration(expected = generation, actual = callbackGeneration)
        }
        return transition(event)
    }

    /** Runs [block] only when [callbackGeneration] is still current. Returns whether it ran. */
    fun runIfCurrent(callbackGeneration: Long, block: () -> Unit): Boolean {
        if (!isCurrent(callbackGeneration)) return false
        block()
        return true
    }

    /**
     * Begins teardown: bumps the generation (invalidating every outstanding
     * callback) and moves to CLOSING unless already CLOSED. Returns the new
     * generation — callbacks scheduled after teardown must use it.
     */
    fun beginTeardown(): Long {
        generation++
        if (state != SessionState.CLOSED) {
            state = SessionState.CLOSING
        }
        return generation
    }

    /** Completes teardown from CLOSING/ERROR; a no-op in any other state. */
    fun completeTeardown(): TransitionResult =
        if (state == SessionState.CLOSING || state == SessionState.ERROR) {
            val from = state
            state = SessionState.CLOSED
            TransitionResult.Applied(from, SessionState.CLOSED)
        } else {
            TransitionResult.Rejected(state, SessionEvent.DEVICE_CLOSED)
        }

    companion object {
        /** The full legal-transition table; anything absent is rejected. */
        fun legalTarget(from: SessionState, event: SessionEvent): SessionState? = when (from) {
            SessionState.CLOSED -> when (event) {
                SessionEvent.OPEN_REQUESTED -> SessionState.OPENING
                else -> null
            }
            SessionState.OPENING -> when (event) {
                SessionEvent.DEVICE_OPENED -> SessionState.OPEN
                SessionEvent.CLOSE_REQUESTED -> SessionState.CLOSING
                SessionEvent.ERROR_OCCURRED -> SessionState.ERROR
                else -> null
            }
            SessionState.OPEN -> when (event) {
                SessionEvent.CONFIGURE_REQUESTED -> SessionState.CONFIGURING
                SessionEvent.CLOSE_REQUESTED -> SessionState.CLOSING
                SessionEvent.ERROR_OCCURRED -> SessionState.ERROR
                else -> null
            }
            SessionState.CONFIGURING -> when (event) {
                SessionEvent.SESSION_CONFIGURED -> SessionState.STREAMING
                SessionEvent.CONFIGURE_FAILED -> SessionState.OPEN
                SessionEvent.CLOSE_REQUESTED -> SessionState.CLOSING
                SessionEvent.ERROR_OCCURRED -> SessionState.ERROR
                else -> null
            }
            SessionState.STREAMING -> when (event) {
                SessionEvent.CONFIGURE_REQUESTED -> SessionState.CONFIGURING
                SessionEvent.CLOSE_REQUESTED -> SessionState.CLOSING
                SessionEvent.ERROR_OCCURRED -> SessionState.ERROR
                else -> null
            }
            SessionState.CLOSING -> when (event) {
                SessionEvent.DEVICE_CLOSED -> SessionState.CLOSED
                SessionEvent.ERROR_OCCURRED -> SessionState.ERROR
                else -> null
            }
            SessionState.ERROR -> when (event) {
                SessionEvent.CLOSE_REQUESTED -> SessionState.CLOSING
                SessionEvent.DEVICE_CLOSED -> SessionState.CLOSED
                else -> null
            }
        }

        fun isLegal(from: SessionState, event: SessionEvent): Boolean =
            legalTarget(from, event) != null
    }
}
