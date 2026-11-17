package com.vayunmathur.maps.data

/**
 * A name reduced to alphanumerics, lowercased, for the sidecar join.
 *
 * Tile names and sidecar names disagree on case and punctuation far more often
 * than on the words themselves, so an exact match misses places both sides
 * know. Only used to compare two Kotlin-side strings — never against the
 * Rust writer's sort order, so [String.lowercase] (not [asciiLower]) is fine.
 */
internal fun normName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

/** Cheap squared planar distance (deg², lon scaled by cos lat) for ranking. */
internal fun distanceSq(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val cosLat = Math.cos(Math.toRadians((aLat + bLat) / 2.0))
    val dLat = aLat - bLat
    val dLon = (aLon - bLon) * cosLat
    return dLat * dLat + dLon * dLon
}
