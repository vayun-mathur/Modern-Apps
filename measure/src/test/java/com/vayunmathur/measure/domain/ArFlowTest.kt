package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.TrackingQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArFlowTest {

    private val hit = HitCandidate(HitKind.Plane, Vec3(0.0, -1.0, -2.0), 2.2)

    private fun derive(
        unavailable: ArUnavailableReason? = null,
        hasSession: Boolean = true,
        quality: TrackingQuality = TrackingQuality.Good,
        reticleHit: HitCandidate? = hit,
        scanningSinceMs: Long? = null,
        lostSinceMs: Long? = null,
        nowMs: Long = 0L,
    ) = ArFlow.derive(unavailable, hasSession, quality, reticleHit, scanningSinceMs, lostSinceMs, nowMs)

    @Test fun unavailableAlwaysWins() {
        val s = derive(unavailable = ArUnavailableReason.CameraTimestampMismatch)
        assertEquals(ArFlowState.Unavailable(ArUnavailableReason.CameraTimestampMismatch), s)
        assertFalse(ArFlow.canPlace(s))
    }

    @Test fun noSessionBeforeEngineStarts() {
        assertEquals(ArFlowState.NoSession, derive(hasSession = false))
    }

    @Test fun initialisingAndLimitedAreScanningNeverReady() {
        // Even with a hit present, Limited must not place: its scale is a guess.
        assertIs<ArFlowState.Scanning>(derive(quality = TrackingQuality.Initialising))
        assertIs<ArFlowState.Scanning>(derive(quality = TrackingQuality.Limited))
        assertFalse(ArFlow.canPlace(derive(quality = TrackingQuality.Limited)))
    }

    @Test fun goodWithoutHitIsSearchingGoodWithHitIsReady() {
        assertEquals(ArFlowState.Searching, derive(reticleHit = null))
        val ready = derive()
        assertIs<ArFlowState.Ready>(ready)
        assertTrue(ArFlow.canPlace(ready))
    }

    @Test fun scanningTimesOutIntoFallbackOffer() {
        val s = derive(
            quality = TrackingQuality.Initialising,
            scanningSinceMs = 0L,
            nowMs = ArFlow.INITIALISING_TIMEOUT_MS,
        )
        assertIs<ArFlowState.Scanning>(s)
        assertTrue(s.timedOut)
    }

    @Test fun lostTimesOutIndependently() {
        val s = derive(
            quality = TrackingQuality.Lost,
            lostSinceMs = 0L,
            nowMs = ArFlow.LOST_TIMEOUT_MS + 1,
        )
        assertIs<ArFlowState.TrackingLost>(s)
        assertTrue(s.timedOut)
    }
}
