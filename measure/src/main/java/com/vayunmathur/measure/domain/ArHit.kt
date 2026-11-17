package com.vayunmathur.measure.domain

import kotlin.math.sqrt

/** A metric 3D point in the tracker world frame (metres, gravity-aligned). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun distanceTo(other: Vec3): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    fun midpoint(other: Vec3): Vec3 = Vec3((x + other.x) / 2, (y + other.y) / 2, (z + other.z) / 2)
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

/** Kind of a hit-test candidate: a fitted surface plane or a single tracked feature point. */
enum class HitKind { Plane, FeaturePoint }

/**
 * One candidate hit from a hit-test provider, before selection.
 *
 * [angularErrorDeg] is the angle between the centre ray and the candidate
 * (0 for a plane intersection on the ray itself). [normalDotUp] is the
 * absolute dot of the surface normal with gravity-up for plane hits, so
 * 1 = horizontal floor/ceiling, 0 = vertical wall; null for point hits.
 */
data class HitCandidate(
    val kind: HitKind,
    val position: Vec3,
    val distanceM: Double,
    val angularErrorDeg: Double = 0.0,
    val normalDotUp: Double? = null,
)

/**
 * Selects the one hit a reticle/placement may use, or null.
 *
 * This is the policy the old native ray cast lacked: it returned the
 * nearest landmark by perpendicular distance with no threshold at all,
 * so any tap snapped to some sparse point however far off-ray it was,
 * and it intersected a single infinite fitted plane with no distance
 * or extent check. Every candidate must now pass distance and angle
 * sanity, and a real plane hit on the ray always wins over a point
 * that merely happens to lie near it.
 */
object HitSelector {
    /** Thresholds for hit selection: sane distance range, feature-point angle limit, and plane preference. */
    data class Config(
        val minDistanceM: Double = 0.15,
        val maxDistanceM: Double = 8.0,
        val maxFeatureAngleDeg: Double = 2.5,
        /** Prefer a plane even over a slightly closer feature point. */
        val preferPlane: Boolean = true,
    )

    val Default = Config()

    fun select(candidates: List<HitCandidate>, config: Config = Default): HitCandidate? {
        val sane = candidates.filter { isSane(it, config) }
        if (sane.isEmpty()) return null
        val planes = sane.filter { it.kind == HitKind.Plane }
        if (config.preferPlane && planes.isNotEmpty()) {
            return planes.minByOrNull { it.distanceM }
        }
        val features = sane.filter { it.kind == HitKind.FeaturePoint }
        if (features.isNotEmpty()) {
            return features.minWithOrNull(
                compareBy<HitCandidate> { it.angularErrorDeg }.thenBy { it.distanceM }
            )
        }
        return planes.minByOrNull { it.distanceM }
    }

    fun isSane(candidate: HitCandidate, config: Config = Default): Boolean {
        if (!candidate.position.isFinite()) return false
        if (!candidate.distanceM.isFinite()) return false
        if (candidate.distanceM < config.minDistanceM) return false
        if (candidate.distanceM > config.maxDistanceM) return false
        return when (candidate.kind) {
            HitKind.Plane -> {
                val n = candidate.normalDotUp
                // Reject slanted fits: a plane is a floor/ceiling or a wall,
                // never a 45-degree ramp through clutter.
                n == null || n > 0.85 || n < 0.15
            }
            HitKind.FeaturePoint -> candidate.angularErrorDeg <= config.maxFeatureAngleDeg
        }
    }
}
