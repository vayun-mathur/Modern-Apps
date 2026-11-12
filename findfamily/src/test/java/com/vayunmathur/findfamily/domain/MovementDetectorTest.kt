package com.vayunmathur.findfamily.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GitHub #895: the sender's moving/still state machine and its forced-fix policy. */
class MovementDetectorTest {

    private val homeLat = 51.5
    private val homeLon = -0.12

    /** ~111 km per degree of latitude. */
    private fun northOf(meters: Double) = homeLat + meters / 111_195.0

    private fun fix(
        lat: Double = homeLat,
        lon: Double = homeLon,
        acc: Double? = 10.0,
        speed: Double? = null,
        speedAcc: Double? = null,
        at: Long,
    ) = FixSample(lat, lon, acc, speed, speedAcc, at)

    private val minute = 60_000L

    /** A detector that has gone still at home, anchored on an accurate fix. */
    private fun stillAtHome(): Pair<MovementDetector, Long> {
        val d = MovementDetector()
        d.markMoving(0L)
        d.onFix(fix(at = 0L), 0L)
        val t = MovementDetector.FIX_QUIET_MS + minute
        assertEquals(MotionTransition.BECAME_STILL, d.onTick(t))
        assertFalse(d.isMoving)
        return d to t
    }

    @Test
    fun vehicleWithLowAccelerationButDisplacementStaysMoving() {
        val d = MovementDetector()
        d.markMoving(0L)
        // Steady tram ride: acceleration never above 0.5 m/s², fixes 600 m apart, no speed.
        var t = 0L
        var meters = 0.0
        repeat(30) {
            for (s in 0 until 60) {
                assertEquals(MotionTransition.NONE, d.onAcceleration(0.1f, t + s * 1_000L))
            }
            t += minute
            meters += 600.0
            assertEquals(MotionTransition.NONE, d.onFix(fix(lat = northOf(meters), at = t), t))
        }
        assertTrue(d.isMoving, "30 min of displacement must keep the phone moving")
    }

    @Test
    fun vehicleSpeedFromAnyProviderWakesAStillPhone() {
        val (d, t) = stillAtHome()
        // Same spot (no displacement) but the provider reports 12 m/s.
        val fast = fix(speed = 12.0, speedAcc = 1.0, at = t)
        assertEquals(MotionTransition.BECAME_MOVING, d.onFix(fast, t))
        assertTrue(d.isMoving)
    }

    @Test
    fun walkingPaceOrUncertainSpeedDoesNotCountAsVehicle() {
        val (d, t) = stillAtHome()
        assertEquals(MotionTransition.NONE, d.onFix(fix(speed = 1.4, at = t), t))
        // 3 m/s reading with ±2 m/s uncertainty is not clearly above walking.
        assertEquals(MotionTransition.NONE, d.onFix(fix(speed = 3.0, speedAcc = 2.0, at = t + 1), t + 1))
        assertFalse(d.isMoving)
    }

    @Test
    fun coarseFixFarFromHomeWakesMovementDetection() {
        val (d, t) = stillAtHome()
        // 600 m network fix, 3 km away: too coarse to display, but proves departure.
        val coarse = fix(lat = northOf(3_000.0), acc = 600.0, at = t)
        assertEquals(MotionTransition.BECAME_MOVING, d.onFix(coarse, t))
        assertEquals(coarse, d.anchor)
    }

    @Test
    fun networkJitterAtHomeDoesNotWakeMovement() {
        val (d, t) = stillAtHome()
        // 110 m off with a 50 m claimed radius: within 2 x (10 + 50) = 120 m, so no.
        assertEquals(MotionTransition.NONE, d.onFix(fix(lat = northOf(110.0), acc = 50.0, at = t), t))
        // A coarse fix wobbling 400 m with a 300 m radius does not count either.
        val wobble = fix(lat = northOf(400.0), acc = 300.0, at = t + 1)
        assertEquals(MotionTransition.NONE, d.onFix(wobble, t + 1))
        assertFalse(d.isMoving)
    }

    @Test
    fun stationaryPhoneGoesStillOnlyWhenBothSignalsAreQuiet() {
        val d = MovementDetector()
        d.markMoving(0L)
        d.onFix(fix(at = 0L), 0L)
        // Acceleration quiet for 61 s: the old rule would stop here.
        assertEquals(MotionTransition.NONE, d.onAcceleration(0.1f, 61_000L))
        assertTrue(d.isMoving, "acceleration alone must not decide still")
        // Fixes at the same spot keep arriving; still only after the fix window too.
        assertEquals(MotionTransition.NONE, d.onFix(fix(at = 2 * minute), 2 * minute))
        val after = MovementDetector.FIX_QUIET_MS + 1
        assertEquals(MotionTransition.BECAME_STILL, d.onFix(fix(at = after), after))
        assertFalse(d.isMoving)
    }

    @Test
    fun accelerationStillWakesAndHoldsMovement() {
        val (d, t) = stillAtHome()
        assertEquals(MotionTransition.BECAME_MOVING, d.onAcceleration(2.0f, t))
        // Keeps moving while acceleration keeps coming, even with no fixes.
        val later = t + MovementDetector.FIX_QUIET_MS + minute
        assertEquals(MotionTransition.NONE, d.onAcceleration(1.0f, later))
        assertTrue(d.isMoving)
    }

    @Test
    fun replayedOldFixSaysNothing() {
        val (d, t) = stillAtHome()
        val old = fix(lat = northOf(5_000.0), at = t - MovementDetector.MAX_SAMPLE_AGE_MS - 1)
        assertEquals(MotionTransition.NONE, d.onFix(old, t))
        assertFalse(d.isMoving)
    }

    @Test
    fun coarseAnchorIsRefinedSoASlowDepartureIsStillSeen() {
        val d = MovementDetector()
        d.onFix(fix(acc = 800.0, at = 0L), 0L)
        // Accurate fix 300 m away is consistent with the coarse anchor and refines it.
        val sharp = fix(lat = northOf(300.0), acc = 10.0, at = 1L)
        assertEquals(MotionTransition.NONE, d.onFix(sharp, 1L))
        assertEquals(sharp, d.anchor)
        // 150 m on from the refined anchor now counts.
        assertEquals(MotionTransition.BECAME_MOVING, d.onFix(fix(lat = northOf(450.0), at = 2L), 2L))
    }

    @Test
    fun unknownAccuracyIsTreatedAsCoarse() {
        val (d, t) = stillAtHome()
        assertEquals(MotionTransition.NONE, d.onFix(fix(lat = northOf(1_500.0), acc = null, at = t), t))
        val far = fix(lat = northOf(3_000.0), acc = null, at = t + 1)
        assertEquals(MotionTransition.BECAME_MOVING, d.onFix(far, t + 1))
    }

    @Test
    fun forcedFixIntervalTightensWhileCharging() {
        val p = ForcedFixPolicy
        assertEquals(p.INTERVAL_CHARGING_MS, p.intervalMs(charging = true, powerSave = true))
        assertEquals(p.INTERVAL_POWER_SAVE_MS, p.intervalMs(charging = false, powerSave = true))
        assertEquals(p.INTERVAL_DEFAULT_MS, p.intervalMs(charging = false, powerSave = false))
        assertTrue(p.INTERVAL_CHARGING_MS < p.INTERVAL_DEFAULT_MS)
    }

    @Test
    fun staleHeldFixMakesForcedFixDue() {
        val i = ForcedFixPolicy.INTERVAL_DEFAULT_MS
        val now = 100 * minute
        fun due(held: Long?, attempt: Long?) = ForcedFixPolicy.isDue(now, held, attempt, i)
        assertTrue(due(held = null, attempt = null))
        assertFalse(due(held = now - i + 1, attempt = null))
        assertTrue(due(held = now - i, attempt = null))
        // A failed attempt one tick ago must not retry yet.
        assertFalse(due(held = null, attempt = now - 30_000L))
        assertTrue(due(held = null, attempt = now - i))
    }

    @Test
    fun gpsFallbackOnlyWhenNetworkCannotDisplay() {
        fun gps(hasGps: Boolean = true, hasNetwork: Boolean = true, acc: Double?, powerSave: Boolean = false) =
            ForcedFixPolicy.shouldFallBackToGps(hasGps, hasNetwork, acc, powerSave)
        assertTrue(gps(acc = null))
        assertTrue(gps(acc = 400.0))
        assertFalse(gps(acc = 40.0))
        // Power-save keeps GPS to the moving path, unless there is no network at all.
        assertFalse(gps(acc = 400.0, powerSave = true))
        assertTrue(gps(hasNetwork = false, acc = null, powerSave = true))
        assertFalse(gps(hasGps = false, hasNetwork = false, acc = null))
    }

    @Test
    fun newDetectorStartsStillWithNoAnchor() {
        val d = MovementDetector()
        assertFalse(d.isMoving)
        assertNull(d.anchor)
        assertEquals(MotionTransition.NONE, d.onTick(10 * minute))
    }
}
