package com.vayunmathur.clock.pure

import com.vayunmathur.clock.platform.DualTimeBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dual wall/monotonic base: wall jumps must not move a running stopwatch or
 * countdown, and a reboot (monotonic reset) must fall back to the wall base.
 * Pure millisecond arithmetic mirroring how ClockViewModel, the stopwatch /
 * timer receivers and BootReceiver feed DualTimeBase.
 */
class DualTimeBaseTest {
    // A run started at wall 1_700_000_000_000 with 3_600_000 ms uptime.
    private val wallStart = 1_700_000_000_000L
    private val monoStart = 3_600_000L
    private val hourMs = 3_600_000L

    @Test
    fun wallJumpForwardDoesNotChangeStopwatchElapsed() {
        // One real hour passes; wall clock also jumps +6h (timezone change).
        val elapsed = DualTimeBase.stopwatchElapsedMs(
            totalMs = 90_000L,
            wallStartMs = wallStart,
            monoStartMs = monoStart,
            wallNowMs = wallStart + hourMs + 6 * hourMs,
            monoNowMs = monoStart + hourMs,
        )
        assertEquals(90_000L + hourMs, elapsed)
    }

    @Test
    fun wallJumpBackwardDoesNotChangeStopwatchElapsed() {
        val elapsed = DualTimeBase.elapsedMs(
            wallStart, monoStart,
            wallNowMs = wallStart - 2 * hourMs,
            monoNowMs = monoStart + 60_000L,
        )
        assertEquals(60_000L, elapsed)
    }

    @Test
    fun wallJumpDoesNotChangeTimerRemaining() {
        // 5-minute timer, 1 minute really elapsed, wall jumps +1h.
        val remaining = DualTimeBase.timerRemainingMs(
            remainingMs = 300_000L,
            wallStartMs = wallStart,
            monoStartMs = monoStart,
            wallNowMs = wallStart + hourMs,
            monoNowMs = monoStart + 60_000L,
        )
        assertEquals(240_000L, remaining)
    }

    @Test
    fun rebootFallsBackToWallForStopwatch() {
        // After reboot mono resets below the stored anchor; wall kept running.
        assertFalse(DualTimeBase.isMonotonicUsable(monoStart, nowMonoMs = 120_000L))
        val elapsed = DualTimeBase.elapsedMs(
            wallStart, monoStart,
            wallNowMs = wallStart + 2 * hourMs,
            monoNowMs = 120_000L,
        )
        assertEquals(2 * hourMs, elapsed)
    }

    @Test
    fun rebootTimerExpiredWhileOffClampsToZero() {
        val remaining = DualTimeBase.timerRemainingMs(
            remainingMs = 300_000L,
            wallStartMs = wallStart,
            monoStartMs = monoStart,
            wallNowMs = wallStart + hourMs,
            monoNowMs = 5_000L, // reset by reboot
        )
        assertEquals(0L, remaining)
    }

    @Test
    fun rebootTimerStillRunningUsesWallSpan() {
        val remaining = DualTimeBase.timerRemainingMs(
            remainingMs = 3 * hourMs,
            wallStartMs = wallStart,
            monoStartMs = monoStart,
            wallNowMs = wallStart + hourMs,
            monoNowMs = 5_000L,
        )
        assertEquals(2 * hourMs, remaining)
    }

    @Test
    fun legacyRowWithoutMonoAnchorUsesWall() {
        val elapsed = DualTimeBase.elapsedMs(
            wallStart, monoStartMs = null,
            wallNowMs = wallStart + 30_000L,
            monoNowMs = monoStart + 999_999L,
        )
        assertEquals(30_000L, elapsed)
    }

    @Test
    fun backwardsWallWithNoMonoAnchorClampsToZero() {
        val elapsed = DualTimeBase.elapsedMs(
            wallStart, monoStartMs = null,
            wallNowMs = wallStart - hourMs,
            monoNowMs = null,
        )
        assertEquals(0L, elapsed)
    }

    @Test
    fun monotonicTriggerIsNowMonoPlusRemaining() {
        assertTrue(DualTimeBase.isMonotonicUsable(monoStart, monoStart + 1L))
        assertEquals(
            monoStart + 240_000L,
            DualTimeBase.timerTriggerElapsedMs(monoStart, 240_000L),
        )
        assertNull(DualTimeBase.timerTriggerElapsedMs(null, 240_000L))
    }

    @Test
    fun monoAnchorEqualToNowIsUsableWithZeroElapsed() {
        assertEquals(
            0L,
            DualTimeBase.elapsedMs(wallStart, monoStart, wallStart + hourMs, monoStart),
        )
    }
}
