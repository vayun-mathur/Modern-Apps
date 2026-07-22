package com.vayunmathur.astronomy.domain.engine

import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class TimeEngineTest {

    @OptIn(ExperimentalTime::class)
    @Test
    fun j2000_julianDate() {
        // 2000-01-01 12:00 TT approx = JD 2451545.0 ; UTC off by ~64s but within 0.01 day
        val jd = TimeEngine.J2000
        val inst = TimeEngine.julianDateToInstant(jd)
        val jd2 = TimeEngine.instantToJulianDate(inst)
        assertTrue(abs(jd - jd2) < 0.001, "JD roundtrip $jd vs $jd2")
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun gmst_at_j2000() {
        // At J2000 GMST ~ 280.46 deg per Meeus; verify within 1 deg
        val gmst = TimeEngine.gmstRad(TimeEngine.J2000)
        val expectedDeg = 280.46061837
        val diffDeg = abs(gmst.toDeg() - expectedDeg)
        assertTrue(diffDeg < 1.0, "GMST at J2000 $diffDeg deg off")
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun lst_equals_gmst_at_greenwich() {
        val jd = TimeEngine.J2000
        val gmst = TimeEngine.gmstRad(jd)
        val lst = TimeEngine.lstRad(jd, 0.0)
        assertTrue(abs(lst - gmst) < 1e-9)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun centuriesSinceJ2000_zero_at_j2000() {
        assertTrue(abs(TimeEngine.centuriesSinceJ2000(TimeEngine.J2000)) < 1e-9)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun jd_to_instant_roundtrip_epoch() {
        // Unix epoch 1970-01-01 = JD 2440587.5
        val epochJd = 2440587.5
        val inst = TimeEngine.julianDateToInstant(epochJd)
        assertTrue(abs(inst.toEpochMilliseconds()) < 1000)
        val jd2 = TimeEngine.instantToJulianDate(Instant.fromEpochMilliseconds(0))
        assertTrue(abs(jd2 - epochJd) < 0.001)
    }

    @Test
    fun obliquity_at_j2000_approx_23_44() {
        val eps = TimeEngine.meanObliquityRad(TimeEngine.J2000)
        assertTrue(abs(eps.toDeg() - 23.439291) < 0.05, "eps ${eps.toDeg()}")
    }

    @Test
    fun normalizeHelpers() {
        assertTrue(abs((3 * PI).normalize2Pi() - PI) < 1e-9)
        assertTrue(abs((PI * 1.5).normalizePi() - (-PI * 0.5)) < 1e-9)
    }
}
