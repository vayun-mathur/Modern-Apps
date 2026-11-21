package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveVideoBitratePolicyTest {

    private val plenty = AdaptiveVideoBitratePolicy.LOW_STORAGE_THRESHOLD_BYTES * 4
    private val low = AdaptiveVideoBitratePolicy.LOW_STORAGE_THRESHOLD_BYTES - 1

    private fun decide(
        tier: VideoQualityTier,
        motion: SceneMotion?,
        free: Long? = plenty,
        thermal: ThermalPressure = ThermalPressure.NORMAL,
        boost: Boolean = false,
    ) = AdaptiveVideoBitratePolicy.decide(
        AdaptiveBitrateInput(tier, motion, free, thermal, boost)
    )

    // -- Scene scaling on each rung, no forced reductions --------------------

    @Test
    fun staticScalesToFiftyFivePercent() {
        assertEquals(26_400_000, decide(VideoQualityTier.UHD, SceneMotion.STATIC).bitrateBps)
        assertEquals(13_750_000, decide(VideoQualityTier.FHD, SceneMotion.STATIC).bitrateBps)
        assertEquals(6_600_000, decide(VideoQualityTier.HD, SceneMotion.STATIC).bitrateBps)
        assertEquals(3_300_000, decide(VideoQualityTier.SD, SceneMotion.STATIC).bitrateBps)
    }

    @Test
    fun moderateScalesToEightyPercent() {
        assertEquals(38_400_000, decide(VideoQualityTier.UHD, SceneMotion.MODERATE).bitrateBps)
        assertEquals(20_000_000, decide(VideoQualityTier.FHD, SceneMotion.MODERATE).bitrateBps)
        assertEquals(9_600_000, decide(VideoQualityTier.HD, SceneMotion.MODERATE).bitrateBps)
        assertEquals(4_800_000, decide(VideoQualityTier.SD, SceneMotion.MODERATE).bitrateBps)
    }

    @Test
    fun highMotionUsesRungAsIsWithoutBoostProbe() {
        for (tier in VideoQualityTier.entries) {
            val d = decide(tier, SceneMotion.HIGH_MOTION, boost = false)
            assertEquals(tier.baseBitrateBps, d.bitrateBps, "tier=$tier")
            assertFalse(d.boosted)
        }
    }

    @Test
    fun unknownMotionUsesRungAsIs() {
        // No estimator samples yet must never silently cut quality.
        for (tier in VideoQualityTier.entries) {
            assertEquals(tier.baseBitrateBps, decide(tier, motion = null).bitrateBps, "tier=$tier")
        }
    }

    @Test
    fun highMotionBoostsUhdAndFhdByTwentyFivePercentWhenProbeAllows() {
        assertEquals(60_000_000, decide(VideoQualityTier.UHD, SceneMotion.HIGH_MOTION, boost = true).bitrateBps)
        assertEquals(31_250_000, decide(VideoQualityTier.FHD, SceneMotion.HIGH_MOTION, boost = true).bitrateBps)
        assertTrue(decide(VideoQualityTier.UHD, SceneMotion.HIGH_MOTION, boost = true).boosted)
    }

    @Test
    fun highMotionNeverBoostsHdOrSd() {
        assertEquals(12_000_000, decide(VideoQualityTier.HD, SceneMotion.HIGH_MOTION, boost = true).bitrateBps)
        assertEquals(6_000_000, decide(VideoQualityTier.SD, SceneMotion.HIGH_MOTION, boost = true).bitrateBps)
    }

    // -- Forced step-downs ----------------------------------------------------

    @Test
    fun lowStorageStepsDownOneTier() {
        val d = decide(VideoQualityTier.UHD, SceneMotion.HIGH_MOTION, free = low)
        assertEquals(VideoQualityTier.FHD, d.effectiveTier)
        assertEquals(25_000_000, d.bitrateBps)
        assertEquals(setOf(AdaptiveReduction.LOW_STORAGE), d.reductions)
        assertTrue(d.forcedReduction)
    }

    @Test
    fun severeThermalStepsDownOneTier() {
        val d = decide(VideoQualityTier.FHD, SceneMotion.MODERATE, thermal = ThermalPressure.SEVERE)
        assertEquals(VideoQualityTier.HD, d.effectiveTier)
        assertEquals(9_600_000, d.bitrateBps) // HD rung at the moderate factor
        assertEquals(setOf(AdaptiveReduction.THERMAL), d.reductions)
        assertTrue(d.forcedReduction)
    }

    @Test
    fun storageAndThermalStackToTwoTiers() {
        val d = decide(
            VideoQualityTier.UHD, SceneMotion.HIGH_MOTION,
            free = low, thermal = ThermalPressure.SEVERE, boost = true,
        )
        assertEquals(VideoQualityTier.HD, d.effectiveTier)
        assertEquals(12_000_000, d.bitrateBps)
        assertEquals(
            setOf(AdaptiveReduction.LOW_STORAGE, AdaptiveReduction.THERMAL), d.reductions
        )
        assertFalse(d.boosted, "no quality spend while forced down")
    }

    @Test
    fun sceneFactorAppliesToTheSteppedDownRung() {
        // UHD static + low storage: FHD rung x 0.55, not UHD x 0.55 capped down.
        val d = decide(VideoQualityTier.UHD, SceneMotion.STATIC, free = low)
        assertEquals(VideoQualityTier.FHD, d.effectiveTier)
        assertEquals(13_750_000, d.bitrateBps)
    }

    @Test
    fun sdCannotStepDownFurtherButReductionIsRecorded() {
        val d = decide(
            VideoQualityTier.SD, SceneMotion.HIGH_MOTION,
            free = low, thermal = ThermalPressure.SEVERE,
        )
        assertEquals(VideoQualityTier.SD, d.effectiveTier)
        assertEquals(6_000_000, d.bitrateBps)
        assertEquals(
            setOf(AdaptiveReduction.LOW_STORAGE, AdaptiveReduction.THERMAL), d.reductions
        )
        assertFalse(d.forcedReduction, "bitrate did not actually drop below the SD rung")
    }

    @Test
    fun storageThresholdIsExclusiveAndUnknownProbesNeverForce() {
        val atThreshold = decide(
            VideoQualityTier.UHD, SceneMotion.HIGH_MOTION,
            free = AdaptiveVideoBitratePolicy.LOW_STORAGE_THRESHOLD_BYTES,
        )
        assertEquals(VideoQualityTier.UHD, atThreshold.effectiveTier)
        val unknown = decide(
            VideoQualityTier.UHD, SceneMotion.HIGH_MOTION,
            free = null, thermal = ThermalPressure.UNKNOWN,
        )
        assertEquals(48_000_000, unknown.bitrateBps)
        assertTrue(unknown.reductions.isEmpty())
    }

    @Test
    fun boostIsSuppressedByAStepDown() {
        val d = decide(
            VideoQualityTier.UHD, SceneMotion.HIGH_MOTION,
            thermal = ThermalPressure.SEVERE, boost = true,
        )
        assertEquals(VideoQualityTier.FHD, d.effectiveTier)
        assertEquals(25_000_000, d.bitrateBps)
        assertFalse(d.boosted)
    }

    // -- Whole-matrix properties ----------------------------------------------

    @Test
    fun matrixNeverBreachesFloorOrExceedsBoostedRung() {
        val frees = listOf(plenty, low, null)
        val thermals = ThermalPressure.entries
        for (tier in VideoQualityTier.entries) for (motion in listOf<SceneMotion?>(
            SceneMotion.STATIC, SceneMotion.MODERATE, SceneMotion.HIGH_MOTION, null
        )) for (free in frees) for (thermal in thermals) for (boost in listOf(false, true)) {
            val d = decide(tier, motion, free, thermal, boost)
            val floor = (d.effectiveTier.baseBitrateBps *
                AdaptiveVideoBitratePolicy.QUALITY_FLOOR_FRACTION).toInt()
            assertTrue(
                d.bitrateBps >= floor,
                "below floor: tier=$tier motion=$motion free=$free thermal=$thermal -> $d"
            )
            assertTrue(
                d.bitrateBps <= (tier.baseBitrateBps *
                    AdaptiveVideoBitratePolicy.HIGH_MOTION_BOOST_FACTOR).toInt(),
                "above boosted base rung: tier=$tier motion=$motion free=$free thermal=$thermal -> $d"
            )
            // A step-down may never raise the bitrate above the base rung either.
            if (d.effectiveTier != tier) {
                assertTrue(d.bitrateBps <= tier.baseBitrateBps, "step-down raised bitrate: $d")
            }
        }
    }

    @Test
    fun ladderValuesAreTheStaticLadder() {
        // Guards the single source the Recorder ladder is built from.
        assertEquals(48_000_000, VideoQualityTier.UHD.baseBitrateBps)
        assertEquals(25_000_000, VideoQualityTier.FHD.baseBitrateBps)
        assertEquals(12_000_000, VideoQualityTier.HD.baseBitrateBps)
        assertEquals(6_000_000, VideoQualityTier.SD.baseBitrateBps)
        assertEquals(VideoQualityTier.FHD, VideoQualityTier.UHD.lower())
        assertEquals(VideoQualityTier.SD, VideoQualityTier.SD.lower())
    }
}
