package com.vayunmathur.weather.domain.wx

import com.vayunmathur.weather.network.WxAq
import com.vayunmathur.weather.network.WxBundle
import com.vayunmathur.weather.network.WxCurrent
import com.vayunmathur.weather.network.WxDaily
import com.vayunmathur.weather.network.WxHourly
import com.vayunmathur.weather.network.WxMin15
import java.time.LocalDate
import java.util.Locale
import java.util.TreeMap

/**
 * Port of the server's `weather/compute.rs::compute_bundle`: the `wx/v1`
 * bundle from one point's model series, computed on device from a static R2
 * tile. Must stay operation-for-operation identical to the Rust (parity test
 * `WxParityTest` runs server-generated fixtures through it).
 */
internal object WxCompute {
    private const val FETCH_MARGIN = 24
    private const val N_FETCH = 337 + FETCH_MARGIN
    private const val N_OUT = 337
    private const val NO_CODE = Int.MIN_VALUE
    private const val SUNNY_SW = 1000.0

    class CurrentGap : Exception("current step unavailable (chunk read gap)")

    private fun r2(v: Double) = WxMath.round2(v)

    /** Local calendar date (yyyy-mm-dd) of [epoch] in the zone table. */
    fun localDay(z: WxTiles.Zone, epoch: Long): String {
        val d = LocalDate.ofEpochDay(Math.floorDiv(epoch + z.offsetAt(epoch), 86400L))
        return String.format(Locale.ROOT, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
    }

    private class Steps(n: Int) {
        val temp = DoubleArray(n) { Double.NaN }
        val feels = DoubleArray(n) { Double.NaN }
        val rh = DoubleArray(n) { Double.NaN }
        val dew = DoubleArray(n) { Double.NaN }
        val code = IntArray(n) { NO_CODE }
        val pprob = IntArray(n)
        val precip = DoubleArray(n) { Double.NaN }
        val wspd = DoubleArray(n) { Double.NaN }
        val wdir = IntArray(n)
        val pres = DoubleArray(n) { Double.NaN }
        val vis = DoubleArray(n) { Double.NaN }
        val cloud = IntArray(n)
        val gust = DoubleArray(n) { Double.NaN }
        val day = IntArray(n)
        val sw = DoubleArray(n) { Double.NaN }
    }

    private fun get(m: Map<String, FloatArray>, k: String, idx: Int): Double =
        m[k]?.let { s -> if (idx < s.size) s[idx].toDouble() else Double.NaN } ?: Double.NaN

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun steps(lat: Double, lon: Double, hFetchLo: Long, lapse: Double, icon: Map<String, FloatArray>): Steps {
        val s = Steps(N_FETCH)
        val sunny = icon[WxGrid.SUNNY_VAR]
        for (k in 0 until N_FETCH) {
            val tRaw = get(icon, "temperature_2m", k)
            val t = if (tRaw.isFinite()) tRaw + lapse else tRaw
            val r = get(icon, "relative_humidity_2m", k)
            val p = get(icon, "precipitation", k)
            val cl = get(icon, "cloud_cover", k)
            val u = get(icon, "wind_u_component_10m", k) / 3.6
            val v = get(icon, "wind_v_component_10m", k) / 3.6
            s.temp[k] = t
            s.rh[k] = r
            s.precip[k] = p
            s.pres[k] = get(icon, "pressure_msl", k)
            s.cloud[k] = if (cl.isFinite()) WxMath.rround(cl).toInt() else 0
            s.dew[k] = WxDerive.dewPoint(t, r) ?: Double.NaN
            val spd = if (u.isFinite() && v.isFinite()) StrictMath.hypot(u, v) else Double.NaN
            s.feels[k] = WxDerive.apparentTemperature(t, r, spd) ?: Double.NaN
            val wc = get(icon, "weather_code", k)
            s.code[k] = if (wc.isFinite()) WxMath.rround(wc).toInt() else NO_CODE
            s.pprob[k] = WxDerive.precipProbability(p, cl, r) ?: 0
            WxDerive.windSpdDir(u, v)?.let { (sp, d) ->
                s.wspd[k] = sp
                s.wdir[k] = d
            }
            s.vis[k] = WxDerive.visibility(p, r) ?: Double.NaN
            s.gust[k] = get(icon, "wind_gusts_10m", k)
            s.sw[k] = if (sunny != null) {
                // Tile: exact sunny flag instead of the radiation pair.
                val f = if (k < sunny.size) sunny[k].toDouble() else Double.NaN
                when {
                    f.isNaN() -> Double.NaN
                    f > 0.5 -> SUNNY_SW
                    else -> 0.0
                }
            } else {
                WxDerive.shortwave(get(icon, "diffuse_radiation", k), get(icon, "direct_radiation", k)) ?: Double.NaN
            }
            s.day[k] = if (WxAstro.isDay((hFetchLo + k) * 3600, lat, lon)) 1 else 0
        }
        for (a in listOf(s.temp, s.feels, s.dew, s.precip, s.wspd, s.pres, s.vis, s.gust, s.sw)) {
            for (k in a.indices) if (a[k].isFinite()) a[k] = r2(a[k])
        }
        return s
    }

    private fun codeOut(c: Int) = if (c == NO_CODE) 0 else c

    /** The bundle (all sections) for (lat, lon) at [now]; throws [CurrentGap] like the server's 502. */
    fun computeBundle(lat: Double, lon: Double, now: Long, runIso: String, s: WxPointSeries): WxBundle {
        val w = WxWindows.at(now)
        val lapse = s.elevations?.let { (point, mean) -> WxDerive.lapseCorrection(mean, point) } ?: 0.0
        val st = steps(lat, lon, w.hFetchLo, lapse, s.icon)
        var hourlyLen = N_FETCH
        val firstGap = st.temp.indexOfFirst { !it.isFinite() }
        if (firstGap >= 0) {
            hourlyLen = if ((firstGap until N_FETCH).all { !st.temp[it].isFinite() }) firstGap else 0
        }
        val nowK = maxOf(w.nowH - w.hFetchLo, 0L).toInt()
        val k = minOf(nowK, N_FETCH - 1)
        if (!st.temp[k].isFinite()) throw CurrentGap()
        val current = WxCurrent(
            time = w.nowH * 3600, temp = r2(st.temp[k]), feels = r2(st.feels[k]),
            rh = WxMath.rround(st.rh[k]).toInt(), dew = r2(st.dew[k]), code = codeOut(st.code[k]),
            windSpd = r2(st.wspd[k]), windDir = st.wdir[k], pres = r2(st.pres[k]), vis = r2(st.vis[k]),
            cloud = st.cloud[k], gust = r2(st.gust[k]), day = st.day[k],
        )
        val uv = s.cams[WxGrid.CAMS_UV_VAR]
        val hourly = if (hourlyLen > FETCH_MARGIN) hourly(w, st, hourlyLen, uv) else null
        val daily = if (hourlyLen > FETCH_MARGIN) daily(lat, lon, s.tz, now, w.hFetchLo, st, hourlyLen, uv) else null
        return WxBundle(
            v = 1,
            lat = WxMath.rround(lat * 100.0) / 100.0,
            lon = WxMath.rround(lon * 100.0) / 100.0,
            tz = s.tzName,
            tzAbbr = s.tz.abbrAt(now),
            utcOffset = s.tz.offsetAt(now),
            model = "dwd_icon",
            run = runIso,
            current = current,
            hourly = hourly,
            daily = daily,
            min15 = min15(w, s.precipM),
            aq = aq(w, s.cams, s.pollen),
        )
    }

    private fun hourly(w: WxWindows, st: Steps, hourlyLen: Int, uv: FloatArray?): WxHourly {
        val n = minOf(hourlyLen - FETCH_MARGIN, N_OUT)
        val r = FETCH_MARGIN until FETCH_MARGIN + n
        fun sl(a: DoubleArray) = r.map { a[it] }
        fun sli(a: IntArray) = r.map { a[it] }
        return WxHourly(
            start = w.hLo * 3600, step = 3600, count = n,
            temp = sl(st.temp), feels = sl(st.feels),
            rh = r.map { if (st.rh[it].isFinite()) WxMath.rround(st.rh[it]).toInt() else 0 },
            dew = sl(st.dew), code = r.map { codeOut(st.code[it]) }, pprob = sli(st.pprob), precip = sl(st.precip),
            windSpd = sl(st.wspd), windDir = sli(st.wdir), pres = sl(st.pres), vis = sl(st.vis),
            uv = List(n) { kk ->
                val v = if (uv != null && kk < uv.size) uv[kk].toDouble() else Double.NaN
                if (v.isFinite()) r2(v) else null
            },
            cloud = sli(st.cloud), gust = sl(st.gust), day = sli(st.day),
        )
    }

    private fun min15(w: WxWindows, precipM: FloatArray): WxMin15? {
        val vals = ArrayList<Double>(288)
        for (k in 0 until 72) {
            val p = if (k < precipM.size) precipM[k].toDouble() else Double.NaN
            if (!p.isFinite()) return null
            val q = r2(p / 4.0)
            repeat(4) { vals.add(q) }
        }
        return WxMin15(start = w.mLo * 3600, step = 900, count = 288, precip = vals)
    }

    private fun aq(w: WxWindows, cams: Map<String, FloatArray>, pollen: Map<String, FloatArray>): WxAq? {
        val k = maxOf(w.nowH - w.hLo, 0L).toInt()
        fun at(m: Map<String, FloatArray>, key: String): Double =
            m[key]?.let { s -> if (k < s.size) s[k].toDouble() else Double.NaN } ?: Double.NaN
        fun mean8(key: String): Double {
            val s = cams[key] ?: return Double.NaN
            val end = minOf(k, maxOf(s.size - 1, 0))
            return WxAqi.trailingMean8h(DoubleArray(s.size) { s[it].toDouble() }, end) ?: Double.NaN
        }
        val aqi = if (cams.containsKey(WxGrid.US_AQI_VAR)) {
            // Tile: the generator's per-hour AQI (same computation).
            at(cams, WxGrid.US_AQI_VAR).takeIf { !it.isNaN() }?.toInt() ?: return null
        } else {
            WxAqi.usAqi(
                at(cams, "pm2_5"), at(cams, "pm10"), mean8("ozone"),
                at(cams, "nitrogen_dioxide"), at(cams, "sulphur_dioxide"), mean8("carbon_monoxide"),
            ) ?: return null
        }
        fun opt(key: String): Double? = at(pollen, key).let { if (it.isFinite()) r2(it) else null }
        return WxAq(
            time = w.nowH * 3600, usAqi = aqi, grass = opt("grass_pollen"), birch = opt("birch_pollen"),
            olive = opt("olive_pollen"), alder = opt("alder_pollen"), ragweed = opt("ragweed_pollen"),
            mugwort = opt("mugwort_pollen"),
        )
    }

    private fun foldMax(idxs: List<Int>, f: (Int) -> Double): Double =
        idxs.map(f).filter { it.isFinite() }.fold(Double.NaN, WxMath::fmax)

    private fun foldMin(idxs: List<Int>, f: (Int) -> Double): Double =
        idxs.map(f).filter { it.isFinite() }.fold(Double.NaN, WxMath::fmin)

    /** Rust `max_by(partial_cmp → Equal on NaN)`: ties keep the LATER index. */
    private fun maxPrecipIdx(idxs: List<Int>, precip: DoubleArray): Int {
        var best = idxs[0]
        for (y in idxs.drop(1)) {
            val a = precip[best]
            val b = precip[y]
            val greater = !a.isNaN() && !b.isNaN() && a > b
            if (!greater) best = y
        }
        return best
    }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private fun daily(
        lat: Double,
        lon: Double,
        z: WxTiles.Zone,
        now: Long,
        hLo: Long,
        st: Steps,
        len: Int,
        uv: FloatArray?,
    ): WxDaily {
        val days = TreeMap<String, MutableList<Int>>()
        for (k in 0 until len) days.getOrPut(localDay(z, (hLo + k) * 3600)) { ArrayList() }.add(k)
        val today = localDay(z, now)
        val keys = days.keys.toList()
        val todayPos = keys.indexOf(today).takeIf { it >= 0 } ?: maxOf(keys.size - 8, 0)
        val startPos = maxOf(todayPos - 7, 0)
        val sel = keys.subList(startPos, minOf(startPos + 15, keys.size))
        val code = ArrayList<Int>()
        val tmax = ArrayList<Double>()
        val tmin = ArrayList<Double>()
        val fmax = ArrayList<Double>()
        val fmin = ArrayList<Double>()
        val sunrise = ArrayList<Long>()
        val sunset = ArrayList<Long>()
        val daylight = ArrayList<Long>()
        val sunshine = ArrayList<Long>()
        val uvMax = ArrayList<Double?>()
        val pprobMax = ArrayList<Int>()
        val precipSum = ArrayList<Double>()
        val moonPhase = ArrayList<Double>()
        val moonrise = ArrayList<Long?>()
        val moonset = ArrayList<Long?>()
        for (key in sel) {
            val idxs = days.getValue(key)
            tmax.add(foldMax(idxs) { st.temp[it] })
            tmin.add(foldMin(idxs) { st.temp[it] })
            fmax.add(foldMax(idxs) { st.feels[it] })
            fmin.add(foldMin(idxs) { st.feels[it] })
            var sum = -0.0 // Rust's float Sum starts at −0.0
            for (k in idxs) if (st.precip[k].isFinite()) sum += st.precip[k]
            precipSum.add(r2(sum))
            pprobMax.add(idxs.maxOf { st.pprob[it] })
            uvMax.add(
                uv?.let { u ->
                    // UV starts 24 h after the fetch window (no margin).
                    val best = foldMax(idxs) { k ->
                        if (k in 24 until 24 + u.size) u[k - 24].toDouble() else Double.NaN
                    }
                    if (best.isFinite()) r2(best) else null
                },
            )
            code.add(codeOut(st.code[maxPrecipIdx(idxs, st.precip)]))
            sunshine.add(idxs.count { st.sw[it].isFinite() && st.sw[it] > WxConsts.SUNSHINE_SW_WM2 }.toLong() * 3600)
            val anchor = (hLo + idxs[0]) * 3600
            val off = z.offsetAt(anchor)
            val (sr, ss) = WxAstro.sunriseSunset(anchor, lat, lon, off)
            if (sr != null && ss != null) {
                sunrise.add(sr)
                sunset.add(ss)
                daylight.add(ss - sr)
            } else {
                sunrise.add(0)
                sunset.add(0)
                daylight.add(WxAstro.polarDaylight(anchor, lat))
            }
            val (mr, ms) = WxAstro.moonriseMoonset(anchor, lat, lon, off)
            moonrise.add(mr)
            moonset.add(ms)
            moonPhase.add(r2(WxAstro.moonPhase(anchor + 43200)))
        }
        return WxDaily(
            start = sel.firstOrNull() ?: "", count = sel.size, code = code, tmax = tmax, tmin = tmin,
            feelsMax = fmax, feelsMin = fmin, sunrise = sunrise, sunset = sunset, daylight = daylight,
            sunshine = sunshine, uvMax = uvMax, pprobMax = pprobMax, precipSum = precipSum,
            moonPhase = moonPhase, moonrise = moonrise, moonset = moonset,
        )
    }
}
