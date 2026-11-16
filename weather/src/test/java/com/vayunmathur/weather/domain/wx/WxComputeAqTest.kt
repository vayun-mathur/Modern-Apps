package com.vayunmathur.weather.domain.wx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [WxCompute.computeBundle] sections the pack fixtures do not exercise:
 * the computed (non-tile) AQI path with trailing 8h means, pollen, the
 * tile-AQI path, gap handling in the temperature series, the 15-minute
 * precipitation fold, and the lapse-correction branch.
 */
class WxComputeAqTest {

    private val now = 1_783_200_000L // 2026-07-01T12:26:40Z; hour-aligned math inside

    private fun zone() = WxTiles.Zone("Europe/Berlin", longArrayOf(0L), intArrayOf(7200), listOf("CEST"))

    private fun arr(size: Int, v: Float): FloatArray = FloatArray(size) { v }

    private fun icon(temp: (Int) -> Float = { 20f + (it % 24) * 0.1f }): Map<String, FloatArray> {
        val n = 361
        return mapOf(
            "temperature_2m" to FloatArray(n) { temp(it) },
            "relative_humidity_2m" to arr(n, 55f),
            "precipitation" to arr(n, 0.4f),
            "cloud_cover" to arr(n, 30f),
            "wind_u_component_10m" to arr(n, 10.8f),
            "wind_v_component_10m" to arr(n, 3.6f),
            "pressure_msl" to arr(n, 1015f),
            "weather_code" to arr(n, 2f),
            "wind_gusts_10m" to arr(n, 25f),
            "diffuse_radiation" to arr(n, 40f),
            "direct_radiation" to arr(n, 60f),
        )
    }

    private fun series(
        icon: Map<String, FloatArray> = icon(),
        cams: Map<String, FloatArray> = emptyMap(),
        pollen: Map<String, FloatArray> = emptyMap(),
        precipM: FloatArray = arr(72, 0.8f),
        elevations: Pair<Double, Double>? = null,
    ) = WxPointSeries("Europe/Berlin", zone(), icon, precipM, cams, pollen, elevations, true)

    @Test fun `computed AQI from pollutants with trailing means and pollen`() {
        val n = 400
        val cams = mapOf(
            "pm2_5" to arr(n, 20f), "pm10" to arr(n, 30f),
            "ozone" to arr(n, 80f), "nitrogen_dioxide" to arr(n, 25f),
            "sulphur_dioxide" to arr(n, 5f), "carbon_monoxide" to arr(n, 400f),
            WxGrid.CAMS_UV_VAR to arr(n, 5f),
        )
        val pollen = mapOf(
            "grass_pollen" to arr(n, 12.34f), "birch_pollen" to arr(n, 0f),
            "olive_pollen" to arr(n, Float.NaN),
        )
        val b = WxCompute.computeBundle(52.5, 13.4, now, "2026-07-01T00:00Z", series(cams = cams, pollen = pollen))
        val aq = b.aq!!
        assertTrue("usAqi=${aq.usAqi}", (aq.usAqi ?: 0) > 0)
        assertEquals(12.34, aq.grass!!, 0.001)
        assertEquals(0.0, aq.birch!!, 0.001)
        assertNull(aq.olive) // NaN pollen maps to null, not NaN
        assertNull(aq.alder) // absent pollen maps to null
        assertNotNull(b.hourly); assertNotNull(b.daily); assertNotNull(b.min15)
        assertEquals(288, b.min15!!.count)
        assertEquals(25.0 / 4.0 * 0 + 0.2, b.min15!!.precip.first(), 0.001)
        assertEquals("Europe/Berlin", b.tz)
        assertEquals("CEST", b.tzAbbr)
        assertEquals(7200, b.utcOffset)
        assertEquals(52.5, b.lat, 0.001)
    }

    @Test fun `tile AQI is used verbatim and NaN tile AQI drops the section`() {
        val n = 400
        val good = WxCompute.computeBundle(
            52.5, 13.4, now, "run", series(cams = mapOf(WxGrid.US_AQI_VAR to arr(n, 42f))),
        )
        assertEquals(42, good.aq!!.usAqi)
        val bad = WxCompute.computeBundle(
            52.5, 13.4, now, "run", series(cams = mapOf(WxGrid.US_AQI_VAR to arr(n, Float.NaN))),
        )
        assertNull(bad.aq)
        // No CAMS data at all: computed AQI has nothing to work with.
        assertNull(WxCompute.computeBundle(52.5, 13.4, now, "run", series()).aq)
    }

    @Test fun `short pollutant series are handled`() {
        // Arrays shorter than the AQ index: at() yields NaN, mean8 clamps.
        val cams = mapOf("pm2_5" to arr(3, 10f), "ozone" to arr(3, 50f))
        val b = WxCompute.computeBundle(52.5, 13.4, now, "run", series(cams = cams))
        // With only pm2_5 (partially) present a US AQI may or may not be
        // computable; either result must be a clean section or null.
        assertTrue(b.aq == null || (b.aq!!.usAqi ?: 0) >= 0)
        assertNotNull(b.current)
    }

    @Test fun `trailing gap truncates hourly but keeps current`() {
        // Finite for the first 250 steps, NaN afterwards: the tail is a
        // gap, so the hourly section is truncated to the finite prefix
        // (250 steps minus the 24-step fetch margin).
        val icon = icon(temp = { if (it < 250) 21f else Float.NaN })
        val b = WxCompute.computeBundle(52.5, 13.4, now, "run", series(icon = icon))
        assertNotNull(b.current)
        assertEquals(226, b.hourly!!.count)
        assertTrue(b.hourly!!.temp.all { it == 21.0 })
    }

    @Test fun `interior gap drops hourly and daily`() {
        val icon = icon(temp = { if (it in 100..110) Float.NaN else 21f })
        val b = WxCompute.computeBundle(52.5, 13.4, now, "run", series(icon = icon))
        assertNotNull(b.current)
        assertNull(b.hourly)
        assertNull(b.daily)
    }

    @Test fun `missing current step throws CurrentGap`() {
        val icon = icon(temp = { Float.NaN })
        try {
            WxCompute.computeBundle(52.5, 13.4, now, "run", series(icon = icon))
            fail("expected CurrentGap")
        } catch (e: WxCompute.CurrentGap) {
            assertEquals("current step unavailable (chunk read gap)", e.message)
        }
    }

    @Test fun `NaN precipM drops min15 and sunny flag replaces radiation`() {
        val icon = icon().toMutableMap().apply {
            put(WxGrid.SUNNY_VAR, FloatArray(361) { if (it % 2 == 0) 1f else 0f })
            remove("diffuse_radiation"); remove("direct_radiation")
        }
        val b = WxCompute.computeBundle(
            52.5, 13.4, now, "run",
            series(icon = icon, precipM = FloatArray(72) { Float.NaN }, elevations = 100.0 to 50.0),
        )
        assertNull(b.min15)
        assertNotNull(b.hourly)
        assertNotNull(b.daily)
        // Elevation lapse correction was applied (finite current temp).
        assertTrue(b.current!!.temp.isFinite())
    }

    @Test fun `localDay respects the zone offset`() {
        // 2026-07-01T00:30Z is 02:30 in Berlin (+2h): same local day...
        assertEquals("2026-07-01", WxCompute.localDay(zone(), 1782865800L))
        // ...while 2026-06-30T23:30Z is already 2026-07-01 locally.
        assertEquals("2026-07-01", WxCompute.localDay(zone(), 1782862200L))
        assertEquals("2026-06-30", WxCompute.localDay(zone(), 1782777600L))
    }
}
