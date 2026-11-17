package com.vayunmathur.measure.platform

import com.vayunmathur.measure.domain.HitCandidate
import com.vayunmathur.measure.domain.HitSelector
import com.vayunmathur.measure.platform.vio.VioEngine

/**
 * Seam between the AR flow and whatever produces hit candidates.
 *
 * Production wraps the Rust visual-inertial engine; tests and
 * previews use [FakeArHitProvider]. Keeping candidate *selection*
 * ([HitSelector]) on this side of the seam is what makes the hit
 * policy unit-testable without a device, a camera, or native code.
 * The [sessionHandle] argument is retained for the seam's shape; the
 * production provider resolves its engine directly and ignores it.
 */
interface ArHitProvider {
    /** The selected centre-reticle hit right now, or null. */
    fun centreHit(sessionHandle: Long): HitCandidate?
}

/** Production provider: Rust engine candidates through the shared selector. */
class VioArHitProvider(
    private val engine: () -> VioEngine?,
    private val analysisWidth: Int,
    private val analysisHeight: Int,
) : ArHitProvider {
    override fun centreHit(sessionHandle: Long): HitCandidate? {
        val eng = engine() ?: return null
        // Prefer the size the engine was actually calibrated for (the
        // delivered analysis size), falling back to the requested size.
        val w = eng.frameWidth.takeIf { it > 0 } ?: analysisWidth
        val h = eng.frameHeight.takeIf { it > 0 } ?: analysisHeight
        return HitSelector.select(eng.centreCandidates(w, h))
    }
}

/** Deterministic provider for tests/previews. */
class FakeArHitProvider(var hit: HitCandidate? = null) : ArHitProvider {
    override fun centreHit(sessionHandle: Long): HitCandidate? = hit
}
