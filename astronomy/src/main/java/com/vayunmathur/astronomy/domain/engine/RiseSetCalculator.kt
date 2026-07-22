package com.vayunmathur.astronomy.domain.engine

import kotlin.math.*

object RiseSetCalculator {
    data class RiseTransitSet(
        val riseJd: Double?,
        val transitJd: Double?,
        val setJd: Double?,
        val isCircumpolar: Boolean = false,
        val isNeverUp: Boolean = false
    )

    const val H0_SUN_DEG = -0.833
    const val H0_STAR_DEG = -0.5667

    fun calc(jd0: Double, latRad: Double, lonRad: Double, getRaDec: (Double) -> RaDec, h0Deg: Double = H0_STAR_DEG): RiseTransitSet {
        val h0Rad = h0Deg.toRad()
        val jdNoon = jd0 + 0.5
        val raDecNoon = getRaDec(jdNoon)
        fun cosH0(decRad: Double): Double {
            val sinH0 = sin(h0Rad)
            return (sinH0 - sin(latRad) * sin(decRad)) / (cos(latRad) * cos(decRad))
        }
        val cosH = cosH0(raDecNoon.decRad)
        if (cosH > 1.0) return RiseTransitSet(null, null, null, false, true)
        if (cosH < -1.0) {
            val transit = findTransit(jd0, lonRad, getRaDec)
            return RiseTransitSet(null, transit, null, true, false)
        }
        val H0 = acos(cosH.coerceIn(-1.0, 1.0))
        val siderealDay = 1.00273790935
        val transitJd = findTransit(jd0, lonRad, getRaDec) ?: jdNoon
        fun altAt(jd: Double): Double {
            val lst = TimeEngine.lstRad(jd, lonRad)
            val rd = getRaDec(jd)
            return CoordinateTransforms.raDecToAltAz(rd, lst, latRad).altRad
        }
        val riseGuess = transitJd - H0.toDeg() / 360.0 / siderealDay
        val setGuess = transitJd + H0.toDeg() / 360.0 / siderealDay
        val riseJd = findCrossing(riseGuess - 0.1, riseGuess + 0.1, h0Rad, ::altAt, true)
        val setJd = findCrossing(setGuess - 0.1, setGuess + 0.1, h0Rad, ::altAt, false)
        return RiseTransitSet(riseJd, transitJd, setJd)
    }

    private fun findTransit(jd0: Double, lonRad: Double, getRaDec: (Double) -> RaDec): Double? {
        var bestT = 0.5; var bestHa = Double.MAX_VALUE
        for (i in 0..24) {
            val t = i / 24.0; val jd = jd0 + t
            val lst = TimeEngine.lstRad(jd, lonRad); val ra = getRaDec(jd).raRad; val ha = (lst - ra).normalizePi()
            if (abs(ha) < abs(bestHa)) { bestHa = ha; bestT = t }
        }
        var lo = (bestT - 0.1).coerceAtLeast(0.0); var hi = (bestT + 0.1).coerceAtMost(1.0)
        repeat(20) {
            val mid = (lo + hi) / 2; val jdMid = jd0 + mid; val lstMid = TimeEngine.lstRad(jdMid, lonRad); val raMid = getRaDec(jdMid).raRad; val haMid = (lstMid - raMid).normalizePi()
            if (haMid > 0) hi = mid else lo = mid
        }
        return jd0 + (lo + hi) / 2
    }

    private fun findCrossing(jdLo: Double, jdHi: Double, h0Rad: Double, altAt: (Double) -> Double, rising: Boolean): Double? {
        var lo = jdLo; var hi = jdHi
        var fLo = altAt(lo) - h0Rad; var fHi = altAt(hi) - h0Rad
        if (fLo * fHi > 0) {
            val steps = 20; var prevJd = lo; var prevF = fLo
            for (k in 1..steps) {
                val jd = lo + (hi - lo) * k / steps; val f = altAt(jd) - h0Rad
                if (prevF * f <= 0) { lo = prevJd; hi = jd; fLo = prevF; fHi = f; break }
                prevJd = jd; prevF = f
            }
            if (fLo * fHi > 0) return null
        }
        repeat(30) {
            val mid = (lo + hi) / 2; val fMid = altAt(mid) - h0Rad
            if (fLo * fMid <= 0) { hi = mid; fHi = fMid } else { lo = mid; fLo = fMid }
        }
        val result = (lo + hi) / 2
        val eps = 0.0001; val before = altAt(result - eps); val after = altAt(result + eps)
        val isRising = after > before
        if (isRising != rising) return null
        return result
    }

    fun sunRiseSet(jd0: Double, latRad: Double, lonRad: Double): RiseTransitSet =
        calc(jd0, latRad, lonRad, { jd -> SolarCalculator.calc(jd).raDec }, H0_SUN_DEG)

    fun starRiseSet(jd0: Double, latRad: Double, lonRad: Double, raDec: RaDec): RiseTransitSet =
        calc(jd0, latRad, lonRad, { raDec }, H0_STAR_DEG)

    fun moonRiseSet(jd0: Double, latRad: Double, lonRad: Double): RiseTransitSet =
        calc(jd0, latRad, lonRad, { jd -> LunarCalculator.calc(jd).raDec }, H0_STAR_DEG)
}
