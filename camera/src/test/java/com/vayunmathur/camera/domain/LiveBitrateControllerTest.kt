package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveBitrateControllerTest {

    private fun decision(
        tier: VideoQualityTier = VideoQualityTier.UHD,
        motion: SceneMotion? = SceneMotion.HIGH_MOTION,
        free: Long? = AdaptiveVideoBitratePolicy.LOW_STORAGE_THRESHOLD_BYTES * 4,
        thermal: ThermalPressure = ThermalPressure.NORMAL,
        boost: Boolean = false,
    ) = AdaptiveVideoBitratePolicy.decide(AdaptiveBitrateInput(tier, motion, free, thermal, boost))

    private fun controller(
        d: AdaptiveBitrateDecision = decision(),
        intervalMs: Long = 2_000L,
    ) = LiveBitrateController(d, intervalMs)

    /** Exactly the two consecutive evaluations a change needs; returns the applied bitrate. */
    private fun drive(
        c: LiveBitrateController,
        motion: SceneMotion?,
        startMs: Long,
    ): Int? {
        c.evaluate(motion, startMs)
        return c.evaluate(motion, startMs + 1_000L)
    }

    @Test
    fun startsAtThePreRecordingDecision() {
        val d = decision(motion = SceneMotion.STATIC)
        assertEquals(26_400_000, controller(d).currentBitrateBps)
    }

    @Test
    fun boundsFollowTheDecision() {
        val boosted = controller(decision(boost = true))
        assertEquals(60_000_000, boosted.ceilingBps)
        assertEquals(19_200_000, boosted.floorBps)

        // Forced down (storage): live motion may not spend the rung back.
        val forced = controller(
            decision(free = AdaptiveVideoBitratePolicy.LOW_STORAGE_THRESHOLD_BYTES - 1)
        )
        assertEquals(VideoQualityTier.FHD, forcedFloorTier(forced))
        assertEquals(25_000_000, forced.ceilingBps)
        assertEquals(10_000_000, forced.floorBps)
    }

    private fun forcedFloorTier(c: LiveBitrateController) =
        VideoQualityTier.entries.first { (it.baseBitrateBps * 0.40).toInt() == c.floorBps }

    @Test
    fun sustainedStaticSceneRampsDownInSlewLimitedSteps() {
        val c = controller() // starts at UHD 48 Mbps
        // One winning candidate needs 2 consecutive evaluations; each applied
        // step moves at most 25% toward it: 48 -> 36 -> 27 -> 26.4 (candidate).
        assertEquals(36_000_000, drive(c, SceneMotion.STATIC, 0L))
        assertEquals(27_000_000, drive(c, SceneMotion.STATIC, 10_000L))
        // The exact candidate (26.4M) is now within 10% of current: the
        // anti-churn threshold deliberately holds at 27M instead of micro-stepping.
        assertEquals(null, drive(c, SceneMotion.STATIC, 20_000L))
        assertEquals(27_000_000, c.currentBitrateBps)
    }

    @Test
    fun aSingleEvaluationNeverChangesAnything() {
        val c = controller()
        assertNull(c.evaluate(SceneMotion.STATIC, 0L))
        assertEquals(48_000_000, c.currentBitrateBps)
    }

    @Test
    fun alternatingMotionDoesNotOscillate() {
        val c = controller()
        var applied = 0
        for (i in 0 until 40) {
            val motion = if (i % 2 == 0) SceneMotion.STATIC else SceneMotion.HIGH_MOTION
            if (c.evaluate(motion, i * 1_000L) != null) applied++
        }
        assertEquals(0, applied, "alternating candidates never win twice in a row")
        assertEquals(48_000_000, c.currentBitrateBps)
    }

    @Test
    fun evaluationsInsideTheIntervalAreHeld() {
        val c = controller()
        // Win the first change at t=1000 (second consecutive evaluation).
        assertNull(c.evaluate(SceneMotion.STATIC, 0L))
        assertEquals(36_000_000, c.evaluate(SceneMotion.STATIC, 1_000L))
        // A reversal inside the 2 s hold is ignored even with two wins.
        assertNull(c.evaluate(SceneMotion.HIGH_MOTION, 1_500L))
        assertNull(c.evaluate(SceneMotion.HIGH_MOTION, 2_000L))
        assertEquals(36_000_000, c.currentBitrateBps)
    }

    @Test
    fun smallCandidateDifferencesAreIgnored() {
        // Moderate (38.4M) -> static candidate is a >10% move, but a controller
        // already at the moderate bitrate sees high-motion (48M) as +25%: applied.
        // Here: HD high (12M) with a moderate candidate (9.6M) is a 20% move: applied.
        val c = controller(decision(tier = VideoQualityTier.HD))
        assertEquals(12_000_000, c.currentBitrateBps)
        val applied = drive(c, SceneMotion.MODERATE, 0L)
        assertEquals(9_600_000, applied)
    }

    @Test
    fun candidatesClampToFloorAndCeiling() {
        val forced = decision(
            tier = VideoQualityTier.UHD,
            free = AdaptiveVideoBitratePolicy.LOW_STORAGE_THRESHOLD_BYTES - 1,
        )
        val c = controller(forced) // effective FHD, starts 25M
        assertEquals(25_000_000, c.candidateFor(SceneMotion.HIGH_MOTION)) // ceiling
        assertEquals(13_750_000, c.candidateFor(SceneMotion.STATIC)) // FHD x 0.55, above floor
        assertEquals(25_000_000, c.candidateFor(null)) // unknown motion: ceiling-wards, no cut
    }

    // -- Live adaptation on the only recording path ---------------------------

    @Test
    fun liveAdaptationWorksWithoutAnyPathSelection() {
        // There is no recording-path policy left to consult: the controller
        // alone bounds live changes between the decision floor and ceiling.
        val c = controller()
        assertEquals(48_000_000, c.candidateFor(SceneMotion.HIGH_MOTION))
        assertEquals(48_000_000, c.candidateFor(null))
        assertEquals(36_000_000, drive(c, SceneMotion.STATIC, 0L))
        assertEquals(36_000_000, c.currentBitrateBps)
    }
}
