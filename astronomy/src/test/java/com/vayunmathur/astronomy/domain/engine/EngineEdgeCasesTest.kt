package com.vayunmathur.astronomy.domain.engine

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge and boundary tests for the engine helpers the happy-path suites skip. */
class EngineEdgeCasesTest {

    @Test
    fun `phase names cover every band and boundary`() {
        assertEquals("New Moon", LunarCalculator.phaseName(0.0))
        assertEquals("New Moon", LunarCalculator.phaseName(0.02))
        assertEquals("New Moon", LunarCalculator.phaseName(0.98))
        assertEquals("New Moon", LunarCalculator.phaseName(1.0))
        assertEquals("Waxing Crescent", LunarCalculator.phaseName(0.03))
        assertEquals("Waxing Crescent", LunarCalculator.phaseName(0.21))
        assertEquals("First Quarter", LunarCalculator.phaseName(0.22))
        assertEquals("First Quarter", LunarCalculator.phaseName(0.27))
        assertEquals("Waxing Gibbous", LunarCalculator.phaseName(0.28))
        assertEquals("Waxing Gibbous", LunarCalculator.phaseName(0.46))
        assertEquals("Full Moon", LunarCalculator.phaseName(0.47))
        assertEquals("Full Moon", LunarCalculator.phaseName(0.52))
        assertEquals("Waning Gibbous", LunarCalculator.phaseName(0.53))
        assertEquals("Waning Gibbous", LunarCalculator.phaseName(0.71))
        assertEquals("Last Quarter", LunarCalculator.phaseName(0.72))
        assertEquals("Last Quarter", LunarCalculator.phaseName(0.77))
        assertEquals("Waning Crescent", LunarCalculator.phaseName(0.78))
        assertEquals("Waning Crescent", LunarCalculator.phaseName(0.96))
    }

    @Test
    fun `batch transform agrees with the one-by-one transform`() {
        val radecs = listOf(
            RaDec(0.0, 0.0),
            RaDec(1.0, 0.5),
            RaDec(3.0, -0.4),
            RaDec(5.5, 1.2),
        )
        val lst = 1.3
        val lat = 0.65
        assertEquals(emptyList(), CoordinateTransforms.batchRaDecToAltAz(emptyList(), lst, lat))
        val batch = CoordinateTransforms.batchRaDecToAltAz(radecs, lst, lat)
        assertEquals(radecs.size, batch.size)
        radecs.forEachIndexed { index, raDec ->
            val single = CoordinateTransforms.raDecToAltAz(raDec, lst, lat)
            assertEquals(single.azRad, batch[index].azRad, 1e-9, "az[$index]")
            assertEquals(single.altRad, batch[index].altRad, 1e-9, "alt[$index]")
        }
    }

    @Test
    fun `refraction is zero below the horizon band and shrinks with altitude`() {
        assertEquals(0.0, CoordinateTransforms.atmosphericRefractionDeg(-1.5))
        assertEquals(0.0, CoordinateTransforms.atmosphericRefractionDeg(-2.0))
        val horizon = CoordinateTransforms.atmosphericRefractionDeg(0.0)
        assertTrue(horizon > 0.4 && horizon < 0.6, "horizon refraction $horizon")
        val ten = CoordinateTransforms.atmosphericRefractionDeg(10.0)
        val fortyFive = CoordinateTransforms.atmosphericRefractionDeg(45.0)
        assertTrue(ten < horizon, "10deg $ten vs horizon $horizon")
        assertTrue(fortyFive < ten, "45deg $fortyFive vs 10deg $ten")
        assertTrue(fortyFive > 0.0)
    }

    @Test
    fun `rise-set for a circumpolar object has a transit but no rise or set`() {
        // At lat +80, an object at dec +85 never sets.
        val lat = 80.0 * PI / 180.0
        val result = RiseSetCalculator.calc(2460000.5, lat, 0.0, { RaDec(1.0, 85.0 * PI / 180.0) })
        assertTrue(result.isCircumpolar)
        assertEquals(false, result.isNeverUp)
        assertNotNull(result.transitJd)
        assertNull(result.riseJd)
        assertNull(result.setJd)
    }

    @Test
    fun `rise-set for a never-up object reports no events`() {
        val lat = 80.0 * PI / 180.0
        val result = RiseSetCalculator.calc(2460000.5, lat, 0.0, { RaDec(1.0, -85.0 * PI / 180.0) })
        assertTrue(result.isNeverUp)
        assertEquals(false, result.isCircumpolar)
        assertNull(result.riseJd)
        assertNull(result.transitJd)
        assertNull(result.setJd)
    }

    @Test
    fun `rise-set for an equatorial object brackets the transit`() {
        val lat = 37.0 * PI / 180.0
        val jd0 = 2460000.5
        val result = RiseSetCalculator.calc(jd0, lat, 0.0, { RaDec(1.0, 0.0) })
        assertEquals(false, result.isCircumpolar)
        assertEquals(false, result.isNeverUp)
        assertNotNull(result.riseJd)
        assertNotNull(result.transitJd)
        assertNotNull(result.setJd)
        assertTrue(result.riseJd!! < result.transitJd!!)
        assertTrue(result.transitJd!! < result.setJd!!)
        // Everything happens within the requested day (plus the crossing search margin).
        assertTrue(result.riseJd!! > jd0 - 0.25 && result.setJd!! < jd0 + 1.25)
    }

    @Test
    fun `rise-set for a fast-moving object still finds its crossings`() {
        // A moon-like provider: RA sweeps half a radian across the day, so
        // the fixed rise/set guesses bracket the crossings only loosely and
        // the sub-scan has to find them.
        val lat = 37.0 * PI / 180.0
        val jd0 = 2460000.5
        val moving = RiseSetCalculator.calc(jd0, lat, 0.0, { jd ->
            RaDec(1.0 + (jd - jd0) * 0.5, 0.1)
        })
        assertEquals(false, moving.isNeverUp)
        assertEquals(false, moving.isCircumpolar)
        assertNotNull(moving.riseJd)
        assertNotNull(moving.setJd)
        assertTrue(moving.riseJd!! < moving.setJd!!)
    }

    @Test
    fun `rise-set honours the sun horizon depression`() {
        val lat = 37.0 * PI / 180.0
        val jd0 = 2460000.5
        val star = RiseSetCalculator.calc(jd0, lat, 0.0, { RaDec(1.0, 0.0) }, RiseSetCalculator.H0_STAR_DEG)
        val sun = RiseSetCalculator.calc(jd0, lat, 0.0, { RaDec(1.0, 0.0) }, RiseSetCalculator.H0_SUN_DEG)
        assertNotNull(sun.riseJd)
        assertNotNull(star.riseJd)
        // A deeper horizon depression means an earlier rise and a later set.
        assertTrue(sun.riseJd!! < star.riseJd!!)
        assertTrue(sun.setJd!! > star.setJd!!)
        assertEquals(-0.833, RiseSetCalculator.H0_SUN_DEG)
        assertEquals(-0.5667, RiseSetCalculator.H0_STAR_DEG)
    }
}
