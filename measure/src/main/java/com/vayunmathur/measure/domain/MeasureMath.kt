package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.distanceTo

/** One measured edge between consecutive anchors. */
data class Segment(
    val startIndex: Int,
    val lengthM: Double,
    /** World-space midpoint, for the on-screen distance label. */
    val midpoint: Vec3,
)

fun Anchor.toVec3(): Vec3 = Vec3(x, y, z)

/** Every consecutive edge, in placement order (the open polyline). */
fun segmentsOf(anchors: List<Anchor>): List<Segment> {
    if (anchors.size < 2) return emptyList()
    return (0 until anchors.size - 1).map { i ->
        val a = anchors[i]
        val b = anchors[i + 1]
        Segment(
            startIndex = i,
            lengthM = a.distanceTo(b),
            midpoint = a.toVec3().midpoint(b.toVec3()),
        )
    }
}

/** Total length of the open polyline through all anchors, in metres. */
fun polylineLengthM(anchors: List<Anchor>): Double? =
    if (anchors.size < 2) null else segmentsOf(anchors).sumOf { it.lengthM }

/** Length of the most recent edge, in metres. */
fun lastSegmentLengthM(anchors: List<Anchor>): Double? =
    segmentsOf(anchors).lastOrNull()?.lengthM

/**
 * Area is only trustworthy for a (near-)planar polygon. The old flow
 * quoted an area for any closed tap loop however bent out of plane it
 * was; callers must now check this warning before presenting a number.
 */
fun areaIsTrustworthy(anchors: List<Anchor>, maxPlanarityErrorM: Double = 0.05): Boolean =
    anchors.size >= 3 && planarityError(anchors) <= maxPlanarityErrorM
