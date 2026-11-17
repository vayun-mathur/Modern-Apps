package com.vayunmathur.measure.platform

import com.vayunmathur.measure.domain.HitCandidate
import com.vayunmathur.measure.domain.HitSelector
import com.vayunmathur.measure.domain.MeasureNative

/**
 * Seam between the AR flow and whatever produces hit candidates.
 *
 * Production wraps the native VIO engine; tests and previews use
 * [FakeArHitProvider]. Keeping candidate *selection* ([HitSelector])
 * on this side of the seam is what makes the hit policy unit-testable
 * without a device, a camera, or the `.so`.
 */
interface ArHitProvider {
    /** The selected centre-reticle hit right now, or null. */
    fun centreHit(sessionHandle: Long): HitCandidate?
}

/** Production provider: native candidates through the shared selector. */
class NativeArHitProvider(
    private val centrePx: () -> Pair<Float, Float>,
) : ArHitProvider {
    override fun centreHit(sessionHandle: Long): HitCandidate? {
        if (sessionHandle == 0L) return null
        val (px, py) = centrePx()
        val candidates = MeasureNative.hitTest(sessionHandle, px, py)
        return HitSelector.select(candidates)
    }
}

/** Deterministic provider for tests/previews. */
class FakeArHitProvider(var hit: HitCandidate? = null) : ArHitProvider {
    override fun centreHit(sessionHandle: Long): HitCandidate? = hit
}
