package com.vayunmathur.weather.domain.wx

import kotlin.math.abs

/**
 * Port of the server's `weather/derive.rs` + `aqi.rs` (location_share_server)
 * for the on-device bundle ([WxCompute]). Every function mirrors its Rust
 * counterpart operation for operation: the app must produce the server's
 * bundle bit for bit (parity test `WxParityTest`). Transcendentals go
 * through [StrictMath] (fdlibm) so every device computes the same thing.
 */
internal object WxMath {
    /** Rust `f64::round`: half away from zero (Kotlin's `round` is half-even). */
    fun rround(x: Double): Double {
        if (x.isNaN() || x.isInfinite()) return x
        val t = if (x < 0) StrictMath.ceil(x) else StrictMath.floor(x)
        return if (abs(x - t) >= 0.5) t + StrictMath.signum(x) else t
    }

    /** Rust `f64::rem_euclid`. */
    fun remEuclid(a: Double, b: Double): Double {
        val r = a % b
        return if (r < 0.0) r + abs(b) else r
    }

    /** Rust `f64::max` (NaN ignored). */
    fun fmax(a: Double, b: Double): Double = when {
        a.isNaN() -> b
        b.isNaN() -> a
        else -> if (a >= b) a else b
    }

    /** Rust `f64::min` (NaN ignored). */
    fun fmin(a: Double, b: Double): Double = when {
        a.isNaN() -> b
        b.isNaN() -> a
        else -> if (a <= b) a else b
    }

    /** Rust `f64::asinh` (std's own formula, not libm). */
    fun asinh(x: Double): Double {
        val ax = abs(x)
        val ix = 1.0 / ax
        val r = StrictMath.log1p(ax + ax / (StrictMath.hypot(1.0, ix) + ix))
        return StrictMath.copySign(r, x)
    }

    fun round2(v: Double): Double = rround(v * 100.0) / 100.0
}

/** `derive::consts`. */
internal object WxConsts {
    const val MAGNUS_B = 17.625
    const val MAGNUS_C = 243.04
    const val ES0_HPA = 6.112
    const val AT_VP = 0.35
    const val AT_WIND = 0.48
    const val AT_OFFSET = 4.5
    const val CALM_KMH = 0.5
    const val MS_TO_KMH = 3.6
    const val VIS_PRECIP_MIN = 0.1
    const val VIS_WET_M = 1000.0
    const val VIS_DAMP_M = 8000.0
    const val VIS_HAZE_M = 15000.0
    const val VIS_CLEAR_M = 24000.0
    const val VIS_EXP_SCALE = 20000.0
    const val VIS_EXP_K = 1.5
    const val VIS_RH_WET = 95.0
    const val VIS_RH_DAMP = 85.0
    const val VIS_RH_HAZE = 75.0
    const val PPROB_HEAVY_MM = 0.5
    const val PPROB_LIGHT_MM = 0.1
    const val PPROB_HEAVY = 90
    const val PPROB_LIGHT = 70
    const val PPROB_OVERCAST_WET = 40
    const val PPROB_CLOUDY = 20
    const val PPROB_BASE = 5
    const val PPROB_OVERCAST_CLOUD = 80.0
    const val PPROB_OVERCAST_RH = 85.0
    const val PPROB_CLOUDY_CLOUD = 50.0
    const val SUNSHINE_SW_WM2 = 120.0
}

/** `derive.rs` per-step derivations (NaN in → null out). */
internal object WxDerive {
    /** Stored → display unit map (`UNIT_SCALE`): only the wind vars scale. */
    private val UNIT_SCALE = mapOf(
        "wind_u_component_10m" to 3.6f,
        "wind_v_component_10m" to 3.6f,
        "wind_gusts_10m" to 3.6f,
    )

    /** `fetch_window` applies the map only when it is not the identity. */
    fun applyUnitSeries(varName: String, v: FloatArray): FloatArray {
        val s = UNIT_SCALE[varName] ?: return v
        return FloatArray(v.size) { k -> if (v[k].isNaN()) Float.NaN else v[k] * s + 0.0f }
    }

    private fun svp(t: Double): Double =
        WxConsts.ES0_HPA * StrictMath.exp((WxConsts.MAGNUS_B * t) / (WxConsts.MAGNUS_C + t))

    fun dewPoint(t: Double, rhPct: Double): Double? {
        if (!t.isFinite() || !rhPct.isFinite() || rhPct <= 0.0) return null
        val rh = WxMath.fmin(rhPct, 100.0)
        val e = rh / 100.0 * svp(t)
        val ln = StrictMath.log(e / WxConsts.ES0_HPA)
        return WxConsts.MAGNUS_C * ln / (WxConsts.MAGNUS_B - ln)
    }

    fun apparentTemperature(t: Double, rhPct: Double, windMs: Double): Double? {
        if (!t.isFinite() || !rhPct.isFinite() || !windMs.isFinite()) return null
        val rh = rhPct.coerceIn(0.0, 100.0)
        val e = rh / 100.0 * svp(t)
        return t + WxConsts.AT_VP * e - WxConsts.AT_WIND * windMs - WxConsts.AT_OFFSET
    }

    fun windSpdDir(u: Double, v: Double): Pair<Double, Int>? {
        if (!u.isFinite() || !v.isFinite()) return null
        val spd = StrictMath.sqrt(u * u + v * v) * WxConsts.MS_TO_KMH
        if (spd < WxConsts.CALM_KMH) return spd to 0
        val dir = WxMath.rround(WxMath.remEuclid(180.0 + StrictMath.toDegrees(StrictMath.atan2(u, v)), 360.0)).toInt()
        return spd to dir
    }

    fun visibility(precip: Double, rhPct: Double): Double? {
        val p = precip.takeIf { it.isFinite() }
        val r = rhPct.takeIf { it.isFinite() }
        if (p == null && r == null) return null
        if (p != null && p >= WxConsts.VIS_PRECIP_MIN) {
            return WxMath.fmax(WxConsts.VIS_WET_M, WxConsts.VIS_EXP_SCALE * StrictMath.exp(-WxConsts.VIS_EXP_K * p))
        }
        if (r != null) {
            if (r >= WxConsts.VIS_RH_WET) return WxConsts.VIS_WET_M
            if (r > WxConsts.VIS_RH_DAMP) return WxConsts.VIS_DAMP_M
            if (r > WxConsts.VIS_RH_HAZE) return WxConsts.VIS_HAZE_M
        }
        return WxConsts.VIS_CLEAR_M
    }

    fun precipProbability(precip: Double, cloud: Double, rh: Double): Int? {
        if (!precip.isFinite() && !cloud.isFinite()) return null
        if (precip.isFinite()) {
            if (precip >= WxConsts.PPROB_HEAVY_MM) return WxConsts.PPROB_HEAVY
            if (precip >= WxConsts.PPROB_LIGHT_MM) return WxConsts.PPROB_LIGHT
        }
        if (cloud.isFinite()) {
            if (cloud > WxConsts.PPROB_OVERCAST_CLOUD && rh.isFinite() && rh > WxConsts.PPROB_OVERCAST_RH) {
                return WxConsts.PPROB_OVERCAST_WET
            }
            if (cloud > WxConsts.PPROB_CLOUDY_CLOUD) return WxConsts.PPROB_CLOUDY
        }
        return WxConsts.PPROB_BASE
    }

    fun shortwave(diffuse: Double, direct: Double): Double? =
        if (!diffuse.isFinite() || !direct.isFinite()) null else diffuse + direct

    /** `dem::lapse_correction`. */
    fun lapseCorrection(cellMeanM: Double, pointM: Double): Double = (cellMeanM - pointM) * (6.5 / 1000.0)
}

/** `aqi.rs`: US EPA AQI from CAMS concentrations. */
internal object WxAqi {
    private class Bp(val cLo: Double, val cHi: Double, val iLo: Int, val iHi: Int)

    private val PM25 = listOf(
        Bp(0.0, 12.0, 0, 50), Bp(12.1, 35.4, 51, 100), Bp(35.5, 55.4, 101, 150),
        Bp(55.5, 150.4, 151, 200), Bp(150.5, 250.4, 201, 300), Bp(250.5, 350.4, 301, 400),
        Bp(350.5, 500.0, 401, 500),
    )
    private val PM10 = listOf(
        Bp(0.0, 54.0, 0, 50), Bp(55.0, 154.0, 51, 100), Bp(155.0, 254.0, 101, 150),
        Bp(255.0, 354.0, 151, 200), Bp(355.0, 424.0, 201, 300), Bp(425.0, 504.0, 301, 400),
        Bp(505.0, 604.0, 401, 500),
    )
    private val O3 = listOf(
        Bp(0.0, 53.0, 0, 50), Bp(54.0, 100.0, 51, 100), Bp(101.0, 168.0, 101, 150),
        Bp(169.0, 208.0, 151, 200), Bp(209.0, 748.0, 201, 300),
    )
    private val NO2 = listOf(
        Bp(0.0, 53.0, 0, 50), Bp(54.0, 100.0, 51, 100), Bp(101.0, 360.0, 101, 150),
        Bp(361.0, 649.0, 151, 200), Bp(650.0, 1249.0, 201, 300), Bp(1250.0, 2049.0, 301, 400),
    )
    private val SO2 = listOf(
        Bp(0.0, 35.0, 0, 50), Bp(36.0, 75.0, 51, 100), Bp(76.0, 185.0, 101, 150), Bp(186.0, 304.0, 151, 200),
    )
    private val CO = listOf(
        Bp(0.0, 4.4, 0, 50), Bp(4.5, 9.4, 51, 100), Bp(9.5, 12.4, 101, 150), Bp(12.5, 15.4, 151, 200),
        Bp(15.5, 30.4, 201, 300), Bp(30.5, 40.4, 301, 400), Bp(40.5, 50.4, 401, 500),
    )

    private fun truncate(v: Double, decimals: Int): Double {
        var m = 1.0
        repeat(decimals) { m *= 10.0 }
        return StrictMath.floor(v * m) / m
    }

    private fun subIndex(conc: Double, table: List<Bp>): Int? {
        if (!conc.isFinite() || conc < 0.0) return null
        for (bp in table) {
            if (conc <= bp.cHi) {
                val c = WxMath.fmax(conc, bp.cLo)
                val i = (bp.iHi - bp.iLo).toDouble() / (bp.cHi - bp.cLo) * (c - bp.cLo) + bp.iLo.toDouble()
                return WxMath.rround(i).toInt()
            }
        }
        return null
    }

    private fun ppb(ugm3: Double, molar: Double) = ugm3 * 24.45 / molar
    private fun ppm(ugm3: Double, molar: Double) = ugm3 * 24.45 / molar / 1000.0

    /** Trailing 8-h mean ending at [hourIdx] (inclusive), null when all NaN. */
    fun trailingMean8h(series: DoubleArray, hourIdx: Int): Double? {
        if (series.isEmpty()) return null
        val end = minOf(hourIdx, series.size - 1)
        val start = maxOf(end - 7, 0)
        var sum = 0.0
        var n = 0
        for (k in start..end) {
            if (series[k].isFinite()) {
                sum += series[k]
                n++
            }
        }
        return if (n == 0) null else sum / n
    }

    /** Overall AQI = max sub-index; null when every pollutant is missing. */
    @Suppress("LongParameterList")
    fun usAqi(pm25: Double, pm10: Double, o38h: Double, no2: Double, so2: Double, co8h: Double): Int? {
        val subs = listOf(
            subIndex(truncate(pm25, 1), PM25),
            subIndex(truncate(pm10, 0), PM10),
            subIndex(truncate(ppb(o38h, 48.0), 0), O3),
            subIndex(ppb(no2, 46.0), NO2),
            subIndex(ppb(so2, 64.0), SO2),
            subIndex(truncate(ppm(co8h, 28.0), 1), CO),
        )
        return subs.filterNotNull().maxOrNull()
    }
}
