package com.vayunmathur.camera.platform.camera2

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class SessionStateMachineTest {

    private fun streamingMachine(): SessionStateMachine {
        val m = SessionStateMachine()
        m.transition(SessionEvent.OPEN_REQUESTED)
        m.transition(SessionEvent.DEVICE_OPENED)
        m.transition(SessionEvent.CONFIGURE_REQUESTED)
        m.transition(SessionEvent.SESSION_CONFIGURED)
        return m
    }

    @Test fun happyPathClosedToStreaming() {
        val m = SessionStateMachine()
        assertEquals(SessionState.CLOSED, m.state)
        assertIs<TransitionResult.Applied>(m.transition(SessionEvent.OPEN_REQUESTED))
        assertEquals(SessionState.OPENING, m.state)
        assertIs<TransitionResult.Applied>(m.transition(SessionEvent.DEVICE_OPENED))
        assertEquals(SessionState.OPEN, m.state)
        assertIs<TransitionResult.Applied>(m.transition(SessionEvent.CONFIGURE_REQUESTED))
        assertEquals(SessionState.CONFIGURING, m.state)
        assertIs<TransitionResult.Applied>(m.transition(SessionEvent.SESSION_CONFIGURED))
        assertEquals(SessionState.STREAMING, m.state)
    }

    @Test fun illegalTransitionsAreRejectedWithoutMutating() {
        val m = SessionStateMachine()
        assertIs<TransitionResult.Rejected>(m.transition(SessionEvent.DEVICE_OPENED))
        assertIs<TransitionResult.Rejected>(m.transition(SessionEvent.SESSION_CONFIGURED))
        assertIs<TransitionResult.Rejected>(m.transition(SessionEvent.DEVICE_CLOSED))
        assertEquals(SessionState.CLOSED, m.state)

        val streaming = streamingMachine()
        assertIs<TransitionResult.Rejected>(streaming.transition(SessionEvent.OPEN_REQUESTED))
        assertIs<TransitionResult.Rejected>(streaming.transition(SessionEvent.DEVICE_OPENED))
        assertEquals(SessionState.STREAMING, streaming.state)
    }

    @Test fun configureFailedFallsBackToOpenAndCanRetry() {
        val m = SessionStateMachine()
        m.transition(SessionEvent.OPEN_REQUESTED)
        m.transition(SessionEvent.DEVICE_OPENED)
        m.transition(SessionEvent.CONFIGURE_REQUESTED)
        assertIs<TransitionResult.Applied>(m.transition(SessionEvent.CONFIGURE_FAILED))
        assertEquals(SessionState.OPEN, m.state)
        assertIs<TransitionResult.Applied>(m.transition(SessionEvent.CONFIGURE_REQUESTED))
        assertEquals(SessionState.CONFIGURING, m.state)
    }

    @Test fun everyStateCanReachClosedThroughTeardownOrError() {
        val m = streamingMachine()
        assertIs<TransitionResult.Applied>(m.transition(SessionEvent.CLOSE_REQUESTED))
        assertEquals(SessionState.CLOSING, m.state)
        assertIs<TransitionResult.Applied>(m.transition(SessionEvent.DEVICE_CLOSED))
        assertEquals(SessionState.CLOSED, m.state)

        val errored = streamingMachine()
        errored.transition(SessionEvent.ERROR_OCCURRED)
        assertEquals(SessionState.ERROR, errored.state)
        assertIs<TransitionResult.Applied>(errored.transition(SessionEvent.DEVICE_CLOSED))
        assertEquals(SessionState.CLOSED, errored.state)
    }

    @Test fun staleGenerationCallbacksAreDropped() {
        val m = streamingMachine()
        val scheduledGeneration = m.generation
        var ran = false

        val newGeneration = m.beginTeardown()
        assertTrue(newGeneration > scheduledGeneration)
        assertEquals(SessionState.CLOSING, m.state)
        assertFalse(m.isCurrent(scheduledGeneration))
        assertTrue(m.isCurrent(newGeneration))

        val result = m.dispatch(scheduledGeneration, SessionEvent.DEVICE_CLOSED)
        assertIs<TransitionResult.StaleGeneration>(result)
        // The stale DEVICE_CLOSED must not have completed the teardown.
        assertEquals(SessionState.CLOSING, m.state)

        assertFalse(m.runIfCurrent(scheduledGeneration) { ran = true })
        assertFalse(ran)
        assertTrue(m.runIfCurrent(newGeneration) { ran = true })
        assertTrue(ran)

        assertIs<TransitionResult.Applied>(m.dispatch(newGeneration, SessionEvent.DEVICE_CLOSED))
        assertEquals(SessionState.CLOSED, m.state)
    }

    @Test fun generationOnlyEverIncreases() {
        val m = SessionStateMachine()
        val g0 = m.generation
        // Teardown from CLOSED still bumps the token, stays CLOSED.
        val g1 = m.beginTeardown()
        assertEquals(SessionState.CLOSED, m.state)
        m.transition(SessionEvent.OPEN_REQUESTED)
        val g2 = m.beginTeardown()
        assertTrue(g0 < g1 && g1 < g2)
        assertEquals(SessionState.CLOSING, m.state)
        m.completeTeardown()
        assertEquals(SessionState.CLOSED, m.state)
        // completeTeardown outside CLOSING/ERROR is a rejected no-op.
        assertIs<TransitionResult.Rejected>(m.completeTeardown())
    }

    @Test fun legalTableAgreesWithMachine() {
        for (from in SessionState.entries) {
            for (event in SessionEvent.entries) {
                val machine = SessionStateMachine()
                // Drive the machine into `from` via the shortest legal path, then probe.
                driveTo(machine, from)
                assertEquals(from, machine.state)
                val result = machine.transition(event)
                assertEquals(
                    SessionStateMachine.isLegal(from, event),
                    result is TransitionResult.Applied,
                    "legality mismatch for $from + $event",
                )
            }
        }
    }

    private fun driveTo(m: SessionStateMachine, target: SessionState) {
        when (target) {
            SessionState.CLOSED -> Unit
            SessionState.OPENING -> m.transition(SessionEvent.OPEN_REQUESTED)
            SessionState.OPEN -> {
                driveTo(m, SessionState.OPENING); m.transition(SessionEvent.DEVICE_OPENED)
            }
            SessionState.CONFIGURING -> {
                driveTo(m, SessionState.OPEN); m.transition(SessionEvent.CONFIGURE_REQUESTED)
            }
            SessionState.STREAMING -> {
                driveTo(m, SessionState.CONFIGURING); m.transition(SessionEvent.SESSION_CONFIGURED)
            }
            SessionState.CLOSING -> {
                driveTo(m, SessionState.OPEN); m.transition(SessionEvent.CLOSE_REQUESTED)
            }
            SessionState.ERROR -> {
                driveTo(m, SessionState.OPEN); m.transition(SessionEvent.ERROR_OCCURRED)
            }
        }
    }
}
