package com.vayunmathur.astronomy.domain.engine

import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertTrue

class CoordinateTransformsTest {

    @Test
    fun raDec_to_AltAz_roundTrip() {
        val lat = 37.7749.toRad()
        val lst = 100.0.toRad()
        val original = RaDec(45.0.toRad(), 20.0.toRad())
        val altAz = CoordinateTransforms.raDecToAltAz(original, lst, lat)
        val back = CoordinateTransforms.altAzToRaDec(altAz, lst, lat)
        val dist = CoordinateTransforms.angularDistanceRad(original, back)
        assertTrue(dist < 1e-6, "roundtrip error $dist rad")
    }

    @Test
    fun raDec_to_AltAz_known_zenith() {
        // At lst == ra and dec == lat => zenith alt 90 deg
        val lat = 37.0.toRad()
        val ra = 120.0.toRad()
        val lst = ra
        val rd = RaDec(ra, lat)
        val aa = CoordinateTransforms.raDecToAltAz(rd, lst, lat)
        assertTrue(abs(aa.altRad.toDeg() - 90.0) < 0.001, "expected zenith but ${aa.altDeg}")
    }

    @Test
    fun batchMatchesSingle() {
        val lat = 0.5; val lst = 1.2
        val list = List(100) { RaDec(it * 0.06, (it % 20 - 10) * 0.05) }
        val batch = CoordinateTransforms.batchRaDecToAltAz(list, lst, lat)
        list.forEachIndexed { i, rd ->
            val single = CoordinateTransforms.raDecToAltAz(rd, lst, lat)
            assertTrue(abs(single.altRad - batch[i].altRad) < 1e-9)
            assertTrue(abs(single.azRad - batch[i].azRad) < 1e-9)
        }
    }

    @Test
    fun ecliptic_roundTrip() {
        val eps = 23.44.toRad()
        val rd = RaDec(60.0.toRad(), 15.0.toRad())
        val ecl = CoordinateTransforms.raDecToEcliptic(rd, eps)
        val back = CoordinateTransforms.eclipticToRaDec(ecl, eps)
        val dist = CoordinateTransforms.angularDistanceRad(rd, back)
        assertTrue(dist < 1e-9, "ecliptic roundtrip $dist")
    }

    @Test
    fun angularDistance_same_zero() {
        val a = RaDec(0.0, 0.0)
        assertTrue(CoordinateTransforms.angularDistanceRad(a, a) < 1e-12)
    }
}
