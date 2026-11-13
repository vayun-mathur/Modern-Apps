package com.vayunmathur.findfamily.domain

import com.vayunmathur.findfamily.domain.ForcedFixPolicy.ACTIVE_CHECK_DUE_MS
import com.vayunmathur.findfamily.domain.ForcedFixPolicy.ACTIVE_CHECK_MAX_GAP_MS
import com.vayunmathur.findfamily.domain.ForcedFixPolicy.INTERVAL_CHARGING_MS
import com.vayunmathur.findfamily.domain.ForcedFixPolicy.INTERVAL_DEFAULT_MS
import com.vayunmathur.findfamily.domain.ForcedFixPolicy.INTERVAL_POWER_SAVE_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 15-minute active-check ceiling (owner requirement on #895) against the existing
 * held-fix staleness intervals, in every power state. Pure: elapsedRealtime-style longs.
 */
class ForcedFixPolicyTest {

    private val minute = 60_000L
    private val t0 = 1_000_000_000L

    @Test
    fun ceilingGuaranteeIsFifteenMinutesAndDueOneMinuteEarly() {
        assertEquals(15 * minute, ACTIVE_CHECK_MAX_GAP_MS)
        assertEquals(14 * minute, ACTIVE_CHECK_DUE_MS)
    }

    @Test
    fun powerStateIntervalsAreUnchanged() {
        assertEquals(5 * minute, ForcedFixPolicy.intervalMs(charging = true, powerSave = false))
        assertEquals(5 * minute, ForcedFixPolicy.intervalMs(charging = true, powerSave = true))
        assertEquals(10 * minute, ForcedFixPolicy.intervalMs(charging = false, powerSave = false))
        assertEquals(15 * minute, ForcedFixPolicy.intervalMs(charging = false, powerSave = true))
    }

    @Test
    fun ceilingIsDueBeforeAnyAttemptAndThenEveryFourteenMinutes() {
        assertTrue(ForcedFixPolicy.isCeilingDue(t0, lastAttemptMs = null))
        assertFalse(ForcedFixPolicy.isCeilingDue(t0 + ACTIVE_CHECK_DUE_MS - 1, lastAttemptMs = t0))
        assertTrue(ForcedFixPolicy.isCeilingDue(t0 + ACTIVE_CHECK_DUE_MS, lastAttemptMs = t0))
    }

    @Test
    fun freshHeldFixNoLongerSuppressesTheCeilingInAnyPowerState() {
        val now = t0 + ACTIVE_CHECK_DUE_MS
        // A passive/low-power fix delivered a second ago keeps the held fix fresh.
        val held = now - 1_000L
        for (interval in listOf(INTERVAL_CHARGING_MS, INTERVAL_DEFAULT_MS, INTERVAL_POWER_SAVE_MS)) {
            assertNull(
                ForcedFixPolicy.dueReason(now, held, lastAttemptMs = t0, interval, ceilingEnabled = false),
                "without the ceiling a fresh held fix suppresses the forced fix (interval $interval)",
            )
            assertEquals(
                ForcedFixReason.ACTIVE_CHECK_CEILING,
                ForcedFixPolicy.dueReason(now, held, lastAttemptMs = t0, interval, ceilingEnabled = true),
                "the ceiling ignores held-fix freshness (interval $interval)",
            )
        }
    }

    @Test
    fun shorterPowerIntervalsStillFireFirstOnAStaleHeldFix() {
        // Charging: a held fix 5 min old with the last attempt 5 min ago is due on staleness,
        // long before the ceiling.
        val now = t0 + INTERVAL_CHARGING_MS
        assertEquals(
            ForcedFixReason.STALE_HELD_FIX,
            ForcedFixPolicy.dueReason(now, heldFixElapsedMs = t0, lastAttemptMs = t0, INTERVAL_CHARGING_MS, true),
        )
        // Battery: 10 min.
        val later = t0 + INTERVAL_DEFAULT_MS
        assertEquals(
            ForcedFixReason.STALE_HELD_FIX,
            ForcedFixPolicy.dueReason(later, heldFixElapsedMs = t0, lastAttemptMs = t0, INTERVAL_DEFAULT_MS, true),
        )
    }

    @Test
    fun ceilingWinsWhenBothAreDue() {
        // Power-save: interval 15 min, ceiling due at 14; at 15 both apply and the ceiling
        // (which may use GPS in power-save) is the reason.
        val now = t0 + INTERVAL_POWER_SAVE_MS
        assertEquals(
            ForcedFixReason.ACTIVE_CHECK_CEILING,
            ForcedFixPolicy.dueReason(now, heldFixElapsedMs = t0, lastAttemptMs = t0, INTERVAL_POWER_SAVE_MS, true),
        )
    }

    @Test
    fun nothingDueRightAfterAnAttempt() {
        val now = t0 + 30_000L
        assertNull(
            ForcedFixPolicy.dueReason(now, heldFixElapsedMs = null, lastAttemptMs = t0, INTERVAL_CHARGING_MS, true),
        )
    }

    @Test
    fun exactAlarmIsArmedForTheMomentTheCheckIsDue() {
        assertEquals(0L, ForcedFixPolicy.ceilingAlarmDelayMs(t0, lastAttemptMs = null, exact = true))
        assertEquals(ACTIVE_CHECK_DUE_MS, ForcedFixPolicy.ceilingAlarmDelayMs(t0, lastAttemptMs = t0, exact = true))
        assertEquals(minute, ForcedFixPolicy.ceilingAlarmDelayMs(t0 + 13 * minute, lastAttemptMs = t0, exact = true))
        assertEquals(0L, ForcedFixPolicy.ceilingAlarmDelayMs(t0 + 20 * minute, lastAttemptMs = t0, exact = true))
    }

    @Test
    fun inexactAlarmChainAlwaysLandsInsideTheCeiling() {
        // Inexact alarms may fire up to 1.75x their delay after being set. Simulate the
        // worst case (every alarm fires at the very end of its window) and the best case
        // (on time) and check the chain reaches a due check before the ceiling.
        for (worstCase in listOf(true, false)) {
            var now = t0
            var wakeUps = 0
            while (!ForcedFixPolicy.isCeilingDue(now, lastAttemptMs = t0)) {
                val delay = ForcedFixPolicy.ceilingAlarmDelayMs(now, lastAttemptMs = t0, exact = false)
                assertTrue(delay > 0L)
                now += if (worstCase) (delay * 1.75).toLong() else delay
                wakeUps++
                assertTrue(now <= t0 + ACTIVE_CHECK_MAX_GAP_MS, "alarm landed after the ceiling (worst=$worstCase)")
                assertTrue(wakeUps < 20, "chain does not converge (worst=$worstCase)")
            }
        }
    }

    @Test
    fun powerSaveUsesGpsOnlyForTheCeiling() {
        // Network answer too coarse to display (500 m > 100 m gate).
        assertFalse(ForcedFixPolicy.shouldFallBackToGps(true, true, 500.0, powerSave = true, ceiling = false))
        assertTrue(ForcedFixPolicy.shouldFallBackToGps(true, true, 500.0, powerSave = true, ceiling = true))
        // No network answer at all.
        assertTrue(ForcedFixPolicy.shouldFallBackToGps(true, true, null, powerSave = true, ceiling = true))
        // A usable network fix never needs GPS.
        assertFalse(ForcedFixPolicy.shouldFallBackToGps(true, true, 30.0, powerSave = true, ceiling = true))
        assertFalse(ForcedFixPolicy.shouldFallBackToGps(true, true, 30.0, powerSave = false, ceiling = true))
        // No GPS provider: nothing to fall back to.
        assertFalse(ForcedFixPolicy.shouldFallBackToGps(false, true, null, powerSave = false, ceiling = true))
        // Off power-save the ceiling behaves like the staleness check.
        assertTrue(ForcedFixPolicy.shouldFallBackToGps(true, true, 500.0, powerSave = false, ceiling = false))
        assertTrue(ForcedFixPolicy.shouldFallBackToGps(true, false, null, powerSave = true, ceiling = false))
    }
}
