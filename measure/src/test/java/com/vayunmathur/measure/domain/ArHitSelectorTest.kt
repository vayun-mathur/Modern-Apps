package com.vayunmathur.measure.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ArHitSelectorTest {

    private fun plane(dist: Double, dotUp: Double? = 1.0) =
        HitCandidate(HitKind.Plane, Vec3(0.0, 0.0, -dist), dist, 0.0, dotUp)

    private fun feature(dist: Double, angleDeg: Double) =
        HitCandidate(HitKind.FeaturePoint, Vec3(0.1, 0.0, -dist), dist, angleDeg, null)

    @Test fun emptyCandidatesYieldNoHit() {
        assertNull(HitSelector.select(emptyList()))
    }

    @Test fun planeOnTheRayWinsOverFeaturePoints() {
        val hit = HitSelector.select(listOf(feature(1.0, 0.1), plane(2.0)))
        assertEquals(HitKind.Plane, hit?.kind)
    }

    @Test fun featureBeyondAngleThresholdIsRejected() {
        // The old native cast returned this unconditionally.
        assertNull(HitSelector.select(listOf(feature(1.0, 20.0))))
    }

    @Test fun featureWithinThresholdIsSelectedByAngle() {
        val hit = HitSelector.select(listOf(feature(1.0, 2.0), feature(3.0, 0.5)))
        assertEquals(0.5, hit?.angularErrorDeg)
    }

    @Test fun tooCloseAndTooFarHitsAreRejected() {
        assertNull(HitSelector.select(listOf(plane(0.05))))
        assertNull(HitSelector.select(listOf(plane(20.0))))
        assertNull(HitSelector.select(listOf(feature(0.01, 0.0))))
    }

    @Test fun slantedPlaneIsRejectedButFloorAndWallPass() {
        assertFalse(HitSelector.isSane(plane(1.0, dotUp = 0.7))) // ~45 deg ramp
        assertTrue(HitSelector.isSane(plane(1.0, dotUp = 0.99)))
        assertTrue(HitSelector.isSane(plane(1.0, dotUp = 0.02)))
    }

    @Test fun nonFiniteHitsAreRejected() {
        assertNull(
            HitSelector.select(
                listOf(HitCandidate(HitKind.Plane, Vec3(Double.NaN, 0.0, 0.0), 1.0))
            )
        )
    }
}
