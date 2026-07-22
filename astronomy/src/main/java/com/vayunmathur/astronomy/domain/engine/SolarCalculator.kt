package com.vayunmathur.astronomy.domain.engine

import kotlin.math.*

object SolarCalculator {
    data class SolarResult(val raDec: RaDec, val distanceAu: Double, val declinationRad: Double, val eclipticLonRad: Double, val equationOfTimeMinutes: Double)

    fun calc(jd: Double): SolarResult {
        val n = jd - TimeEngine.J2000
        var L = (280.460 + 0.9856474 * n) % 360.0
        if (L < 0) L += 360.0
        var g = (357.528 + 0.9856003 * n) % 360.0
        if (g < 0) g += 360.0
        val gRad = g.toRad()
        val lambdaDeg = L + 1.915 * sin(gRad) + 0.020 * sin(2 * gRad)
        val lambdaRad = lambdaDeg.toRad().normalize2Pi()
        val epsRad = TimeEngine.meanObliquityRad(jd)
        val sinDec = sin(epsRad) * sin(lambdaRad)
        val dec = asin(sinDec)
        val y = cos(epsRad) * sin(lambdaRad)
        val x = cos(lambdaRad)
        var ra = atan2(y, x).normalize2Pi()
        val R = 1.00014 - 0.01671 * cos(gRad) - 0.00014 * cos(2 * gRad)
        var eot = (L.toRad() - ra).normalizePi()
        var eotMin = eot * 720.0 / PI
        if (eotMin < -20 && eotMin > -720) {
            // keep near zero crossing
        }
        if (eotMin > 720) eotMin -= 1440.0
        if (eotMin < -720) eotMin += 1440.0
        return SolarResult(RaDec(ra, dec), R, dec, lambdaRad, eotMin)
    }

    fun solarAltAz(jd: Double, lstRad: Double, latRad: Double): AltAz {
        return CoordinateTransforms.raDecToAltAz(calc(jd).raDec, lstRad, latRad)
    }
}
