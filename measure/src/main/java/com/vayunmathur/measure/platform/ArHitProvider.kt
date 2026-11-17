package com.vayunmathur.measure.platform

import com.vayunmathur.measure.domain.HitCandidate
import com.vayunmathur.measure.domain.HitSelector
import com.vayunmathur.measure.platform.opencv.OpenCvVioEngine

/**
 * Seam between the AR flow and whatever produces hit candidates.
 *
 * Production wraps the OpenCV visual-inertial engine; tests and
 * previews use [FakeArHitProvider]. Keeping candidate *selection*
 * ([HitSelector]) on this side of the seam is what makes the hit
 * policy unit-testable without a device, a camera, or native code.
 * The [sessionHandle] argument is retained for the seam's shape; the
 * OpenCV provider resolves its engine directly and ignores it.
 */
interface ArHitProvider {
    /** The selected centre-reticle hit right now, or null. */
    fun centreHit(sessionHandle: Long): HitCandidate?
}

/** Production provider: OpenCV engine candidates through the shared selector. */
class OpenCvArHitProvider(
    private val engine: () -> OpenCvVioEngine?,
    private val analysisWidth: Int,
    private val analysisHeight: Int,
) : ArHitProvider {
    override fun centreHit(sessionHandle: Long): HitCandidate? {
        val eng = engine() ?: return null
        return HitSelector.select(eng.centreCandidates(analysisWidth, analysisHeight))
    }
}

/** Deterministic provider for tests/previews. */
class FakeArHitProvider(var hit: HitCandidate? = null) : ArHitProvider {
    override fun centreHit(sessionHandle: Long): HitCandidate? = hit
}
