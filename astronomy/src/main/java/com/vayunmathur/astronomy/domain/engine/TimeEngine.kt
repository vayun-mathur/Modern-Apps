package com.vayunmathur.astronomy.domain.engine

import kotlin.math.PI
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

object TimeEngine {
    const val J2000 = 2451545.0

    @OptIn(ExperimentalTime::class)
    fun instantToJulianDate(instant: Instant): Double =
        2440587.5 + instant.toEpochMilliseconds() / 86400000.0

    @OptIn(ExperimentalTime::class)
    fun julianDateToInstant(jd: Double): Instant {
        val ms = ((jd - 2440587.5) * 86400000.0).toLong()
        return Instant.fromEpochMilliseconds(ms)
    }

    fun centuriesSinceJ2000(jd: Double): Double = (jd - J2000) / 36525.0
    fun daysSinceJ2000(jd: Double): Double = jd - J2000

    fun gmstRad(jd: Double): Double {
        val T = centuriesSinceJ2000(jd)
        val gmstDeg = 280.46061837 + 360.98564736629 * (jd - J2000) +
            0.000387933 * T * T - T * T * T / 38710000.0
        var g = gmstDeg % 360.0
        if (g < 0) g += 360.0
        return g * PI / 180.0
    }

    fun lstRad(jd: Double, lonRad: Double): Double {
        var lst = gmstRad(jd) + lonRad
        lst %= (2.0 * PI)
        if (lst < 0) lst += 2.0 * PI
        return lst
    }

    fun meanObliquityRad(jd: Double): Double {
        val T = centuriesSinceJ2000(jd)
        val eps0Deg = 23.0 + 26.0 / 60.0 + 21.448 / 3600.0 -
            (46.8150 * T + 0.00059 * T * T - 0.001813 * T * T * T) / 3600.0
        return eps0Deg * PI / 180.0
    }

    fun jdToYear(jd: Double): Double = 2000.0 + (jd - J2000) / 365.2425
}

fun Double.toRad() = this * Math.PI / 180.0
fun Double.toDeg() = this * 180.0 / Math.PI
fun Double.normalize2Pi(): Double { var v = this % (2.0 * Math.PI); if (v < 0) v += 2.0 * Math.PI; return v }
fun Double.normalizePi(): Double { var v = (this + Math.PI) % (2.0 * Math.PI); if (v < 0) v += 2.0 * Math.PI; return v - Math.PI }
