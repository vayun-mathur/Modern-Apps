package com.vayunmathur.astronomy.domain.engine

import kotlin.math.*

object LunarCalculator {
    data class MoonResult(
        val raDec: RaDec,
        val distanceKm: Double,
        val phase: Double,
        val illumination: Double,
        val ageDays: Double,
        val eclipticLonRad: Double,
        val eclipticLatRad: Double
    )

    fun calc(jd: Double): MoonResult {
        val d = jd - TimeEngine.J2000
        fun norm(v: Double): Double { var x = v % 360.0; if (x < 0) x += 360.0; return x }
        val Lp = norm(218.316 + 13.176396 * d)
        val Mm = norm(134.963 + 13.064993 * d)
        val Ms = norm(357.529 + 0.98560028 * d)
        val D2 = norm(297.850 + 12.190749 * d)
        val F2 = norm(93.272 + 13.229350 * d)

        val LpRad = Lp.toRad(); val MmRad = Mm.toRad(); val MsRad = Ms.toRad(); val D2Rad = D2.toRad(); val F2Rad = F2.toRad()

        var sigmaL = 0.0
        sigmaL += 6288774 * sin(MmRad)
        sigmaL += 1274027 * sin(2 * D2Rad - MmRad)
        sigmaL += 658314 * sin(2 * D2Rad)
        sigmaL += 213618 * sin(2 * MmRad)
        sigmaL += -185116 * sin(MsRad)
        sigmaL += -114332 * sin(2 * F2Rad)
        sigmaL += 58793 * sin(2 * D2Rad - 2 * MmRad)
        sigmaL += 57066 * sin(2 * D2Rad - MsRad - MmRad)
        sigmaL += 53322 * sin(2 * D2Rad + MmRad)
        sigmaL += 45728 * sin(2 * D2Rad - MsRad)
        sigmaL += -40923 * sin(MmRad - MsRad)
        sigmaL += -34720 * sin(D2Rad)
        sigmaL += -30383 * sin(MsRad + MmRad)

        var sigmaB = 0.0
        sigmaB += 5128122 * sin(F2Rad)
        sigmaB += 280602 * sin(MmRad + F2Rad)
        sigmaB += 277693 * sin(MmRad - F2Rad)
        sigmaB += 173237 * sin(2 * D2Rad - F2Rad)
        sigmaB += 55413 * sin(2 * D2Rad - MmRad + F2Rad)

        var sigmaR = 0.0
        sigmaR += -20905355 * cos(MmRad)
        sigmaR += -3699111 * cos(2 * D2Rad - MmRad)
        sigmaR += -2955968 * cos(2 * D2Rad)
        sigmaR += -569925 * cos(2 * MmRad)

        val lonDeg = Lp + sigmaL / 1000000.0
        val latDeg = sigmaB / 1000000.0
        val distMeters = 385000560.0 + sigmaR / 1000.0

        val lonRad = lonDeg.toRad().normalize2Pi()
        val latRad = latDeg.toRad()

        val obliq = TimeEngine.meanObliquityRad(jd)
        val raDec = CoordinateTransforms.eclipticToRaDec(Ecliptic(lonRad, latRad), obliq)
        val sun = SolarCalculator.calc(jd)
        val elongation = (lonRad - sun.eclipticLonRad).normalizePi()
        val phase = (D2 / 360.0).let { var v = it % 1.0; if (v < 0) v += 1.0; v }
        val illumination = (1 - cos(elongation)) / 2.0
        val age = phase * 29.53058867
        return MoonResult(raDec, distMeters / 1000.0, phase, illumination.coerceIn(0.0,1.0), age, lonRad, latRad)
    }

    fun phaseName(phase: Double): String = when {
        phase < 0.03 || phase > 0.97 -> "New Moon"
        phase < 0.22 -> "Waxing Crescent"
        phase < 0.28 -> "First Quarter"
        phase < 0.47 -> "Waxing Gibbous"
        phase < 0.53 -> "Full Moon"
        phase < 0.72 -> "Waning Gibbous"
        phase < 0.78 -> "Last Quarter"
        else -> "Waning Crescent"
    }
}
