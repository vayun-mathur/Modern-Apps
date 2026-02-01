package com.vayunmathur.findfamily.data

import kotlinx.serialization.Serializable
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class Coord(val lat: Double, val lon: Double)

fun Coord.toPosition() = Position(lon, lat)

fun radians(degrees: Double) = degrees * PI / 180

fun havershine(p1: Coord, p2: Coord): Double {
    val R = 6371000 // Radius of the earth in m
    val dLat = radians(p2.lat-p1.lat)  // deg2rad below
    val dLon = radians(p2.lon-p1.lon)
    val a = sin(dLat/2) * sin(dLat/2) +
            cos(radians(p1.lat)) * cos(radians(p2.lat)) * sin(dLon/2) * sin(dLon/2)
    val c = 2 * atan2(sqrt(a), sqrt(1-a))
    val d = R * c // Distance in m
    return d
}