package com.vayunmathur.weather.domain.wx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EPA breakpoint behaviour of [WxAqi] plus the Rust-semantics helpers in [WxMath]/[WxDerive]. */
class WxAqiMathTest {

    private fun only(pm25: Double = Double.NaN, pm10: Double = Double.NaN, o3: Double = Double.NaN,
                     no2: Double = Double.NaN, so2: Double = Double.NaN, co: Double = Double.NaN) =
        WxAqi.usAqi(pm25, pm10, o3, no2, so2, co)

    @Test fun pm25Breakpoints() {
        assertEquals(0, only(pm25 = 0.0))
        assertEquals(50, only(pm25 = 12.0))
        assertEquals(51, only(pm25 = 12.1))
        assertEquals(100, only(pm25 = 35.4))
        assertEquals(101, only(pm25 = 35.5))
        assertEquals(150, only(pm25 = 55.4))
        assertEquals(500, only(pm25 = 500.0))
        assertNull(only(pm25 = 500.1)) // above the table: no sub-index
    }

    @Test fun pm25TruncatesToTenthBeforeLookup() {
        // 12.09 truncates to 12.0 -> top of the good band, not 51.
        assertEquals(50, only(pm25 = 12.09))
        // 35.49 truncates to 35.4 -> 100, not 101.
        assertEquals(100, only(pm25 = 35.49))
    }

    @Test fun pm10BreakpointsAndTruncation() {
        assertEquals(50, only(pm10 = 54.0))
        assertEquals(51, only(pm10 = 55.0))
        assertEquals(100, only(pm10 = 154.9)) // truncates to 154
        assertEquals(500, only(pm10 = 604.0))
        assertNull(only(pm10 = 605.0))
    }

    @Test fun overallIsMaxSubIndex() {
        // PM2.5 moderate (75-ish) vs PM10 good: max wins.
        val v = WxAqi.usAqi(20.0, 10.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        assertTrue(v != null && v in 51..100, "got $v")
        // Clean PM but dirty NO2: NO2 200 µg/m³ = 106 ppb -> USG band.
        val no2 = WxAqi.usAqi(0.0, 0.0, Double.NaN, 200.0, Double.NaN, Double.NaN)
        assertTrue(no2 != null && no2 > 100, "got $no2")
    }

    @Test fun missingAndInvalidPollutants() {
        assertNull(only()) // all NaN
        assertNull(only(pm25 = -1.0))
        assertNull(only(pm25 = Double.POSITIVE_INFINITY))
        // One valid pollutant among NaNs still yields an AQI.
        assertEquals(25, only(pm25 = 6.0))
    }

    @Test fun coAndOzoneConversions() {
        // CO 4.4 ppm is the top of the good band: 4.4 ppm = 4400 ppb;
        // ppm = µg/m³ * 24.45 / 28 / 1000 -> µg/m³ = 4.4*28*1000/24.45
        val coGoodTop = 4.4 * 28.0 * 1000.0 / 24.45
        assertEquals(50, only(co = coGoodTop))
        // O3 53 ppb top of good: µg/m³ = 53*48/24.45
        val o3GoodTop = 53.0 * 48.0 / 24.45
        assertEquals(50, only(o3 = o3GoodTop))
    }

    @Test fun trailingMean8h() {
        assertNull(WxAqi.trailingMean8h(DoubleArray(0), 0))
        assertNull(WxAqi.trailingMean8h(doubleArrayOf(Double.NaN, Double.NaN), 1))
        assertEquals(4.5, WxAqi.trailingMean8h(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0), 7))
        // Window is only the trailing 8: first element excluded at idx 8.
        assertEquals(5.5, WxAqi.trailingMean8h(doubleArrayOf(100.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0), 8))
        // NaNs skipped; index clamped to the last element.
        assertEquals(10.0, WxAqi.trailingMean8h(doubleArrayOf(Double.NaN, 10.0), 99))
        // Fewer than 8 hours available: mean of what exists.
        assertEquals(3.0, WxAqi.trailingMean8h(doubleArrayOf(2.0, 4.0), 1))
    }

    @Test fun rustRounding() {
        assertEquals(3.0, WxMath.rround(2.5))
        assertEquals(-3.0, WxMath.rround(-2.5))
        assertEquals(2.0, WxMath.rround(2.49))
        assertEquals(-2.0, WxMath.rround(-2.49))
        assertEquals(Double.NaN, WxMath.rround(Double.NaN))
        assertEquals(Double.POSITIVE_INFINITY, WxMath.rround(Double.POSITIVE_INFINITY))
        assertEquals(0.01, WxMath.round2(0.005))
        assertEquals(-0.01, WxMath.round2(-0.005))
        assertEquals(1.23, WxMath.round2(1.234))
    }

    @Test fun remEuclidAndNaNMinMax() {
        assertEquals(359.0, WxMath.remEuclid(-1.0, 360.0))
        assertEquals(1.0, WxMath.remEuclid(361.0, 360.0))
        assertEquals(0.5, WxMath.remEuclid(0.5, 360.0))
        assertEquals(2.0, WxMath.fmax(Double.NaN, 2.0))
        assertEquals(2.0, WxMath.fmax(2.0, Double.NaN))
        assertEquals(1.0, WxMath.fmin(Double.NaN, 1.0))
        assertEquals(3.0, WxMath.fmax(1.0, 3.0))
        assertEquals(1.0, WxMath.fmin(1.0, 3.0))
    }

    @Test fun asinhIdentities() {
        assertEquals(0.0, WxMath.asinh(0.0), 1e-12)
        assertTrue(kotlin.math.abs(WxMath.asinh(1.0) - 0.881373587019543) < 1e-12)
        assertTrue(kotlin.math.abs(WxMath.asinh(-1.0) + 0.881373587019543) < 1e-12)
        assertTrue(WxMath.asinh(100.0) > 5.2 && WxMath.asinh(100.0) < 5.4)
    }

    @Test fun dewPoint() {
        // Saturated air: dew point equals temperature.
        val dp = WxDerive.dewPoint(20.0, 100.0)!!
        assertTrue(kotlin.math.abs(dp - 20.0) < 0.01, "dp=$dp")
        // Drier air -> lower dew point.
        assertTrue(WxDerive.dewPoint(20.0, 50.0)!! < dp)
        assertNull(WxDerive.dewPoint(20.0, 0.0))
        assertNull(WxDerive.dewPoint(Double.NaN, 50.0))
        assertNull(WxDerive.dewPoint(20.0, Double.NaN))
        // RH above 100 clamps.
        assertTrue(kotlin.math.abs(WxDerive.dewPoint(20.0, 150.0)!! - 20.0) < 0.01)
    }

    @Test fun apparentTemperature() {
        // No wind, saturated: AT = T + 0.35*e - 4.5, e at 30°C is large.
        val hot = WxDerive.apparentTemperature(30.0, 100.0, 0.0)!!
        assertTrue(hot > 30.0, "hot=$hot")
        // Strong wind cools.
        assertTrue(WxDerive.apparentTemperature(30.0, 100.0, 10.0)!! < hot)
        assertNull(WxDerive.apparentTemperature(Double.NaN, 50.0, 1.0))
        assertNull(WxDerive.apparentTemperature(20.0, 50.0, Double.NaN))
    }

    @Test fun windSpdDir() {
        // Calm: direction reported as 0.
        val calm = WxDerive.windSpdDir(0.0, 0.0)!!
        assertEquals(0.0, calm.first, 1e-9); assertEquals(0, calm.second)
        // Pure northerly component (v>0, u=0): wind FROM north -> 180+atan2(0,v)=180? atan2(0,+) =0 -> 180.
        val n = WxDerive.windSpdDir(0.0, 1.0)!!
        assertEquals(3.6, n.first, 1e-9); assertEquals(180, n.second)
        // u>0,v=0: atan2(1,0)=90 -> 270.
        val e = WxDerive.windSpdDir(1.0, 0.0)!!
        assertEquals(270, e.second)
        assertNull(WxDerive.windSpdDir(Double.NaN, 0.0))
    }

    @Test fun visibilityBands() {
        // Precip: 20000*exp(-1.5p), floored at 1000 m.
        assertEquals(20000.0 * StrictMath.exp(-1.5), WxDerive.visibility(1.0, Double.NaN)!!, 1e-9)
        assertEquals(1000.0, WxDerive.visibility(5.0, Double.NaN)) // floor for heavy precip
        assertEquals(1000.0, WxDerive.visibility(Double.NaN, 96.0))
        assertEquals(8000.0, WxDerive.visibility(Double.NaN, 90.0))
        assertEquals(15000.0, WxDerive.visibility(Double.NaN, 80.0))
        assertEquals(24000.0, WxDerive.visibility(Double.NaN, 50.0))
        assertEquals(24000.0, WxDerive.visibility(0.0, Double.NaN))
        assertNull(WxDerive.visibility(Double.NaN, Double.NaN))
        // Light precip still caps below the clear value.
        assertTrue(WxDerive.visibility(0.2, Double.NaN)!! < 24000.0)
    }

    @Test fun precipProbabilityBands() {
        assertEquals(90, WxDerive.precipProbability(0.5, Double.NaN, Double.NaN))
        assertEquals(70, WxDerive.precipProbability(0.1, Double.NaN, Double.NaN))
        assertEquals(40, WxDerive.precipProbability(0.0, 90.0, 90.0))
        assertEquals(20, WxDerive.precipProbability(0.0, 60.0, 50.0))
        assertEquals(5, WxDerive.precipProbability(0.0, 10.0, 10.0))
        assertNull(WxDerive.precipProbability(Double.NaN, Double.NaN, 50.0))
        // Cloud alone (no precip) still yields a probability.
        assertEquals(20, WxDerive.precipProbability(Double.NaN, 60.0, Double.NaN))
    }

    @Test fun shortwaveUnitScaleAndLapse() {
        assertEquals(300.0, WxDerive.shortwave(100.0, 200.0))
        assertNull(WxDerive.shortwave(Double.NaN, 1.0))
        val raw = floatArrayOf(1f, Float.NaN, 2f)
        // Non-wind var: identity (same array contents, no scaling).
        assertTrue(WxDerive.applyUnitSeries("temperature_2m", raw).contentEquals(raw))
        val wind = WxDerive.applyUnitSeries("wind_gusts_10m", floatArrayOf(1f, Float.NaN))
        assertEquals(3.6f, wind[0]); assertTrue(wind[1].isNaN())
        assertEquals(0.65, WxDerive.lapseCorrection(1000.0, 900.0), 1e-9)
        assertEquals(-0.65, WxDerive.lapseCorrection(900.0, 1000.0), 1e-9)
    }

    @Test fun astroMoonAndPolar() {
        val p = WxAstro.moonPhase(947_182_440L + 86400)
        assertTrue(p in 0.0..1.0)
        assertEquals(0.0, WxAstro.moonPhase(947_182_440L), 1e-9)
        // Half a synodic month later: full moon (~0.5).
        val half = WxAstro.moonPhase(947_182_440L + (29.530588853 * 43200).toLong())
        assertTrue(kotlin.math.abs(half - 0.5) < 0.02, "half=$half")
        // Polar day at the north pole in June, polar night in December.
        val june = 1782000000L; val dec = 1798000000L
        assertEquals(86400L, WxAstro.polarDaylight(june, 89.0))
        assertEquals(0L, WxAstro.polarDaylight(dec, 89.0))
        assertEquals(0L, WxAstro.polarDaylight(june, -89.0))
        assertEquals(86400L, WxAstro.polarDaylight(dec, -89.0))
    }

    @Test fun astroSunriseSunset() {
        // Berlin, mid-July 2026: sunrise before sunset, daylight ~16h.
        val day = 1784073600L // 2026-07-15T00:00Z-ish
        val (sr, ss) = WxAstro.sunriseSunset(day, 52.52, 13.40, 7200)
        assertTrue(sr != null && ss != null && ss > sr, "$sr $ss")
        assertTrue(ss!! - sr!! in 14 * 3600..18 * 3600, "daylight=${ss - sr}")
        assertTrue(WxAstro.isDay(sr + 3600, 52.52, 13.40))
        // Polar night: no sunrise/sunset.
        val (psr, pss) = WxAstro.sunriseSunset(1798000000L, 89.0, 0.0, 0)
        assertNull(psr); assertNull(pss)
    }

    @Test fun gridMath() {
        assertEquals(1140 to 1547, WxGrid.cellIndex(WxGrid.ICON, 52.52, 13.41, 1441, 2879))
        // Longitude wraps: 190 == -170.
        assertEquals(
            WxGrid.cellIndex(WxGrid.ICON, 0.0, -170.0, 1441, 2879),
            WxGrid.cellIndex(WxGrid.ICON, 0.0, 190.0, 1441, 2879),
        )
        // Latitude clamps to the grid edge.
        assertEquals(0, WxGrid.cellIndex(WxGrid.ICON, -90.0, 0.0, 1441, 2879).first)
        assertEquals(1440, WxGrid.cellIndex(WxGrid.ICON, 90.0, 0.0, 1441, 2879).first)
        assertTrue(WxGrid.europeContains(52.0, 13.0))
        assertTrue(!WxGrid.europeContains(40.0, -74.0))
        assertTrue(!WxGrid.europeContains(20.0, 13.0))
        assertEquals(0L, WxGrid.hourOf(3599))
        assertEquals(1L, WxGrid.hourOf(3600))
        assertEquals(-1L, WxGrid.hourOf(-1))
        assertNull(WxGrid.geoOf(-89.0, 0.0))
        val k = WxGrid.geoOf(52.52, 13.41)!!
        assertTrue(k.px in 0..255 && k.py in 0..255 && k.b in 0..15)
        // Windows: 337 hourly steps, fetch starts a day earlier.
        val w = WxWindows.at(1784073600L + 1234)
        assertEquals(337L, w.hHi - w.hLo)
        assertEquals(24L, w.hLo - w.hFetchLo)
        assertEquals(72L, w.mHi - w.mLo)
    }
}
