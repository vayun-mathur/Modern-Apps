package com.vayunmathur.findfamily.data

import android.location.Location
import com.vayunmathur.library.map.GeoPoint
import kotlinx.serialization.Serializable
import kotlin.math.PI

@Serializable
/** Geographic coordinate in decimal degrees, embedded in Room rows and carried on the wire. */
data class Coord(val lat: Double, val lon: Double)

fun Coord.toGeoPoint() = GeoPoint(lon, lat)

/** @deprecated Use [toGeoPoint] — kept for transitional compat. */
fun Coord.toPosition() = toGeoPoint()

/** Degrees in a half circle — the divisor converting degrees to radians. */
private const val DEGREES_IN_HALF_CIRCLE = 180.0

fun radians(degrees: Double) = degrees * PI / DEGREES_IN_HALF_CIRCLE

fun havershine(p1: Coord, p2: Coord): Double {
    val results = FloatArray(1)
    Location.distanceBetween(p1.lat, p1.lon, p2.lat, p2.lon, results)
    return results[0].toDouble()
}
