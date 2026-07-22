package com.vayunmathur.astronomy.domain.engine

import com.vayunmathur.astronomy.data.model.OrbitalElements
import kotlin.math.*

object PlanetaryCalculator {
    data class PlanetResult(
        val id: String,
        val name: String,
        val raDec: RaDec,
        val distanceAu: Double,
        val heliocentricDistAu: Double,
        val magnitude: Double?
    )

    fun solveKepler(Mrad: Double, e: Double): Double {
        var E = Mrad
        repeat(20) {
            val delta = (E - e * sin(E) - Mrad) / (1 - e * cos(E))
            E -= delta
            if (abs(delta) < 1e-12) return@repeat
        }
        return E
    }

    fun heliocentricEcliptic(elements: OrbitalElements, jd: Double): Triple<Double, Double, Double> {
        val d = TimeEngine.daysSinceJ2000(jd)
        var Mdeg = elements.m0Deg + elements.nDegPerDay * d
        Mdeg %= 360.0
        if (Mdeg < 0) Mdeg += 360.0
        val Mrad = Mdeg.toRad()
        val Erad = solveKepler(Mrad, elements.e)
        val xv = elements.a * (cos(Erad) - elements.e)
        val yv = elements.a * sqrt(1 - elements.e * elements.e) * sin(Erad)
        val v = atan2(yv, xv)
        val r = sqrt(xv * xv + yv * yv)
        val wRad = elements.wDeg.toRad()
        val omegaRad = elements.omegaDeg.toRad()
        val iRad = elements.iDeg.toRad()
        val u = v + wRad
        val cosU = cos(u); val sinU = sin(u)
        val cosO = cos(omegaRad); val sinO = sin(omegaRad)
        val cosI = cos(iRad); val sinI = sin(iRad)
        val x = r * (cosO * cosU - sinO * sinU * cosI)
        val y = r * (sinO * cosU + cosO * sinU * cosI)
        val z = r * (sinU * sinI)
        return Triple(x, y, z)
    }

    private fun eclipticToEquatorial(x: Double, y: Double, z: Double, obliqRad: Double): Triple<Double, Double, Double> {
        val cosEps = cos(obliqRad); val sinEps = sin(obliqRad)
        return Triple(x, y * cosEps - z * sinEps, y * sinEps + z * cosEps)
    }

    fun geocentricRaDec(planetElements: OrbitalElements, earthElements: OrbitalElements, jd: Double): PlanetResult {
        val obliq = TimeEngine.meanObliquityRad(jd)
        var (xp, yp, zp) = heliocentricEcliptic(planetElements, jd)
        var (xe, ye, ze) = heliocentricEcliptic(earthElements, jd)
        var dx = xp - xe; var dy = yp - ye; var dz = zp - ze
        var dist = sqrt(dx*dx + dy*dy + dz*dz)
        val lightDaysPerAu = 0.0057755183
        val jdCorr = jd - dist * lightDaysPerAu
        val (xp2, yp2, zp2) = heliocentricEcliptic(planetElements, jdCorr)
        val (xe2, ye2, ze2) = heliocentricEcliptic(earthElements, jdCorr)
        dx = xp2 - xe2; dy = yp2 - ye2; dz = zp2 - ze2
        dist = sqrt(dx*dx + dy*dy + dz*dz)
        val rHelio = sqrt(xp2*xp2 + yp2*yp2 + zp2*zp2)
        val (xeq, yeq, zeq) = eclipticToEquatorial(dx, dy, dz, obliq)
        val ra = atan2(yeq, xeq).normalize2Pi()
        val dec = atan2(zeq, sqrt(xeq*xeq + yeq*yeq))
        val mag = estimateMagnitude(planetElements, rHelio, dist, xp2, yp2, zp2, xe2, ye2, ze2)
        return PlanetResult(planetElements.id, planetElements.name, RaDec(ra, dec), dist, rHelio, mag)
    }

    private fun estimateMagnitude(elem: OrbitalElements, r: Double, delta: Double, xp: Double, yp: Double, zp: Double, xe: Double, ye: Double, ze: Double): Double? {
        val base = elem.magBase ?: return null
        val sunPlanet = sqrt(xp*xp + yp*yp + zp*zp)
        val earthPlanet = delta
        val sunEarth = sqrt(xe*xe + ye*ye + ze*ze)
        if (sunPlanet == 0.0 || earthPlanet == 0.0) return base
        val cosPhase = ((sunPlanet*sunPlanet + earthPlanet*earthPlanet - sunEarth*sunEarth) / (2*sunPlanet*earthPlanet)).coerceIn(-1.0,1.0)
        val phaseDeg = acos(cosPhase).toDeg()
        return base + 5.0 * log10(r * delta) + 0.01 * phaseDeg
    }

    fun calcAll(planets: List<OrbitalElements>, earth: OrbitalElements?, jd: Double): List<PlanetResult> {
        if (earth == null) return emptyList()
        return planets.filter { it.id != "EARTH" && it.id != "SUN" && it.id != "MOON_SKIP" }.map { geocentricRaDec(it, earth, jd) }
    }
}
