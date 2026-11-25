package com.vayunmathur.findfamily.wear.domain

import kotlin.math.sqrt

/** Human distance summary: metres under 1 km, one-decimal km beyond. */
fun wearFormatDistance(meters: Double): String = when {
    !meters.isFinite() || meters < 0 -> ""
    meters < 1_000 -> "${meters.toInt()} m"
    else -> "${((meters / 100.0).toInt() / 10.0)} km"
}

/** Short age for a timestamp: "just now", "5m ago", "2h ago", "3d ago"; "" when unknown. */
fun wearAgeLabel(atMillis: Long, nowMillis: Long): String {
    if (atMillis <= 0L) return ""
    val age = (nowMillis - atMillis).coerceAtLeast(0L)
    return when {
        age < 60_000L -> "just now"
        age < 3_600_000L -> "${age / 60_000L}m ago"
        age < 86_400_000L -> "${age / 3_600_000L}h ago"
        else -> "${age / 86_400_000L}d ago"
    }
}

/** Stale label for a person, e.g. "Stale · 2h ago"; empty when fresh. */
fun wearStaleLabel(person: WearPerson, nowMillis: Long): String =
    if (wearIsStale(person, nowMillis)) {
        val age = wearAgeLabel(person.updatedAtMillis, nowMillis)
        if (age.isEmpty()) "No location yet" else "Stale · $age"
    } else {
        ""
    }

/** Distance + compass summary from self to [person]; "" when either end lacks a fix. */
fun wearDistanceSummary(self: WearPerson?, person: WearPerson): String {
    if (self == null || self.latE7 == null || self.lonE7 == null) return ""
    if (person.latE7 == null || person.lonE7 == null || person.isSelf) return ""
    val dist = wearDistanceMeters(self.latE7, self.lonE7, person.latE7, person.lonE7)
    val bearing = wearBearingDegrees(self.latE7, self.lonE7, person.latE7, person.lonE7)
    return "${wearFormatDistance(dist)} ${wearCompass(bearing)}"
}

/**
 * North-up relative projection for the watch Canvas: centre at the
 * region centre, radius fits half the smaller viewport side. Pure so
 * the relative-view math is unit-testable without a Canvas.
 */
data class WearRelativeProjection(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val radiusM: Int,
) {
    val pixelsPerMeter: Double
        get() = (minOf(viewportWidthPx, viewportHeightPx) / 2.0 / radiusM.coerceAtLeast(1))

    /** Screen (x, y) for metres east/north of the centre. North is up. */
    fun project(eastM: Double, northM: Double): Pair<Float, Float> {
        val x = viewportWidthPx / 2.0 + eastM * pixelsPerMeter
        val y = viewportHeightPx / 2.0 - northM * pixelsPerMeter
        return x.toFloat() to y.toFloat()
    }

    /** True when metres (e/n) fall inside the region radius. */
    fun inside(eastM: Double, northM: Double): Boolean =
        sqrt(eastM * eastM + northM * northM) <= radiusM
}
