package com.vayunmathur.weather.domain.wx

import kotlin.math.abs

/**
 * Port of the server's `weather/astro.rs` (NOAA sun, truncated lunar theory)
 * for the on-device bundle. Operation-for-operation identical to the Rust;
 * see [WxMath] for the Rust-semantics helpers.
 */
internal object WxAstro {
    private const val PI = Math.PI
    private const val SUN_ZENITH_DEG = 90.833
    private const val SYNODIC_DAYS = 29.530588853
    private const val REF_NEW_MOON_EPOCH = 947_182_440L

    private fun d2r(d: Double) = d * PI / 180.0
    private fun r2d(r: Double) = r * 180.0 / PI

    private fun julianCentury(epoch: Long): Double {
        val jd = epoch.toDouble() / 86400.0 + 2440587.5
        return (jd - 2451545.0) / 36525.0
    }

    private fun floorMod(a: Long, b: Long) = Math.floorMod(a, b)

    /** `(sunrise, sunset)` of the UTC day containing [dayEpoch], null for polar day/night. */
    @Suppress("LongMethod")
    private fun sunTransitRiseSet(dayEpoch: Long, lat: Double, lon: Double): Pair<Long, Long>? {
        val dayStart = dayEpoch - floorMod(dayEpoch, 86400)
        val t = julianCentury(dayStart + 43200)
        val l0 = WxMath.remEuclid(280.46646 + t * (36000.76983 + t * 0.0003032), 360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val mRad = d2r(m)
        val c = (1.914602 - t * (0.004817 + 0.000014 * t)) * StrictMath.sin(mRad) +
            (0.019993 - 0.000101 * t) * StrictMath.sin(2.0 * mRad) +
            0.000289 * StrictMath.sin(3.0 * mRad)
        val trueLon = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLon - 0.00569 - 0.00478 * StrictMath.sin(d2r(omega))
        val epsilon0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val epsilon = epsilon0 + 0.00256 * StrictMath.cos(d2r(omega))
        val decl = r2d(StrictMath.asin(StrictMath.sin(d2r(epsilon)) * StrictMath.sin(d2r(lambda))))
        val y = StrictMath.cos(d2r(epsilon)) * StrictMath.sin(d2r(lambda))
        val x = StrictMath.cos(d2r(lambda))
        var ra = WxMath.remEuclid(r2d(StrictMath.atan2(y, x)), 360.0)
        ra /= 15.0
        val l0Hours = l0 / 15.0
        val eot = l0Hours * 60.0 - ra * 60.0
        val solarNoonUtcMin = 720.0 - 4.0 * lon - eot
        val latRad = d2r(lat)
        val declRad = d2r(decl)
        val cosH = (StrictMath.cos(d2r(SUN_ZENITH_DEG)) - StrictMath.sin(latRad) * StrictMath.sin(declRad)) /
            (StrictMath.cos(latRad) * StrictMath.cos(declRad))
        if (cosH > 1.0 || cosH < -1.0) return null
        val hMin = r2d(StrictMath.acos(cosH)) * 4.0
        val sunrise = dayStart + WxMath.rround((solarNoonUtcMin - hMin) * 60.0).toLong()
        val sunset = dayStart + WxMath.rround((solarNoonUtcMin + hMin) * 60.0).toLong()
        return sunrise to sunset
    }

    fun polarDaylight(dayEpoch: Long, lat: Double): Long {
        val t = julianCentury(dayEpoch - floorMod(dayEpoch, 86400) + 43200)
        val l0 = WxMath.remEuclid(280.46646 + t * (36000.76983 + t * 0.0003032), 360.0)
        val m = d2r(357.52911 + t * (35999.05029 - 0.0001537 * t))
        val lambda = d2r(l0 + 1.914602 * StrictMath.sin(m))
        val decl = StrictMath.asin(StrictMath.sin(d2r(23.44)) * StrictMath.sin(lambda))
        // Rust f64::signum: ±1 by sign bit (±0.0 → ±1.0).
        val sign = if (lat.isNaN()) Double.NaN else StrictMath.copySign(1.0, lat)
        val maxAlt = 90.0 - abs(lat) + r2d(decl) * sign
        return if (maxAlt > 0.0) 86400 else 0
    }

    fun sunriseSunset(dayEpoch: Long, lat: Double, lon: Double, offsetSecs: Int): Pair<Long?, Long?> {
        val shifted = dayEpoch + offsetSecs
        val shiftedDay = shifted - floorMod(shifted, 86400)
        val r = sunTransitRiseSet(shiftedDay + 43200, lat, lon) ?: return null to null
        return r.first to r.second
    }

    fun isDay(t: Long, lat: Double, lon: Double): Boolean {
        val dayStart = t - floorMod(t, 86400)
        val today = sunTransitRiseSet(t, lat, lon)
        if (today != null && t >= today.first && t < today.second) return true
        val prev = sunTransitRiseSet(dayStart - 43200, lat, lon)
        if (prev != null && t >= prev.first && t < prev.second) return true
        return polarDaylight(t, lat) == 86400L && today == null && prev == null
    }

    fun moonPhase(epoch: Long): Double {
        val days = (epoch - REF_NEW_MOON_EPOCH).toDouble() / 86400.0
        return WxMath.remEuclid(days, SYNODIC_DAYS) / SYNODIC_DAYS
    }

    private fun moonAltitude(lat: Double, lon: Double, epoch: Long): Double {
        val d = epoch.toDouble() / 86400.0 - 10957.5
        val l0 = WxMath.remEuclid(218.316 + 13.176396 * d, 360.0)
        val mm = d2r(WxMath.remEuclid(134.963 + 13.064993 * d, 360.0))
        val ms = d2r(WxMath.remEuclid(357.529 + 0.98560028 * d, 360.0))
        val dd = d2r(WxMath.remEuclid(297.850 + 12.190749 * d, 360.0))
        val ff = d2r(WxMath.remEuclid(93.272 + 13.229350 * d, 360.0))
        val elong = l0 + 6.289 * StrictMath.sin(mm) + 1.274 * StrictMath.sin(2.0 * dd - mm) +
            0.658 * StrictMath.sin(2.0 * dd) - 0.186 * StrictMath.sin(ms) - 0.114 * StrictMath.sin(2.0 * ff)
        val elat = 5.128 * StrictMath.sin(ff)
        val elongR = d2r(elong)
        val elatR = d2r(elat)
        val eps = d2r(23.4393 - 3.563e-7 * d)
        val ra = StrictMath.atan2(
            StrictMath.sin(elongR) * StrictMath.cos(eps) - StrictMath.tan(elatR) * StrictMath.sin(eps),
            StrictMath.cos(elongR),
        )
        val decl = StrictMath.asin(
            StrictMath.sin(elatR) * StrictMath.cos(eps) +
                StrictMath.cos(elatR) * StrictMath.sin(eps) * StrictMath.sin(elongR),
        )
        val gmst = d2r(WxMath.remEuclid(18.697374558 + 24.06570982441908 * d, 24.0) * 15.0)
        var ha = WxMath.remEuclid(gmst + d2r(lon) - ra, 2.0 * PI)
        if (ha > PI) ha -= 2.0 * PI
        val latR = d2r(lat)
        return r2d(
            StrictMath.asin(
                StrictMath.sin(latR) * StrictMath.sin(decl) +
                    StrictMath.cos(latR) * StrictMath.cos(decl) * StrictMath.cos(ha),
            ),
        )
    }

    fun moonriseMoonset(dayEpoch: Long, lat: Double, lon: Double, offsetSecs: Int): Pair<Long?, Long?> {
        val shifted = dayEpoch + offsetSecs
        val start = shifted - floorMod(shifted, 86400) - offsetSecs
        val step = 600L
        var rise: Long? = null
        var set: Long? = null
        var prevT = start
        var prevA = moonAltitude(lat, lon, start)
        var t = start + step
        while (t <= start + 86400) {
            val a = moonAltitude(lat, lon, t)
            if (prevA <= 0.0 && a > 0.0 && rise == null) {
                val f = -prevA / (a - prevA)
                rise = prevT + WxMath.rround((t - prevT).toDouble() * f).toLong()
            } else if (prevA >= 0.0 && a < 0.0 && set == null) {
                val f = prevA / (prevA - a)
                set = prevT + WxMath.rround((t - prevT).toDouble() * f).toLong()
            }
            prevT = t
            prevA = a
            t += step
        }
        return rise to set
    }
}
