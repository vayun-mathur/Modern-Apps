package com.vayunmathur.astronomy.domain.engine

import com.vayunmathur.astronomy.domain.AstronomyNative
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class RaDec(val raRad: Double, val decRad: Double) {
    val raDeg get() = raRad.toDeg()
    val decDeg get() = decRad.toDeg()
    val raHours get() = raDeg / 15.0
}

data class AltAz(val azRad: Double, val altRad: Double) {
    val azDeg get() = azRad.toDeg()
    val altDeg get() = altRad.toDeg()
}

data class Ecliptic(val lonRad: Double, val latRad: Double)

object CoordinateTransforms {

    fun raDecToAltAz(raDec: RaDec, lstRad: Double, latRad: Double): AltAz {
        val ha = (lstRad - raDec.raRad).normalizePi()
        val sinAlt = sin(raDec.decRad) * sin(latRad) + cos(raDec.decRad) * cos(latRad) * cos(ha)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val cosAlt = cos(alt)
        val az = if (abs(cosAlt) < 1e-10) 0.0 else {
            val cosAz = (sin(raDec.decRad) - sinAlt * sin(latRad)) / (cosAlt * cos(latRad))
            val sinAz = -cos(raDec.decRad) * sin(ha) / cosAlt
            atan2(sinAz, cosAz).normalize2Pi()
        }
        return AltAz(az, alt)
    }

    fun altAzToRaDec(altAz: AltAz, lstRad: Double, latRad: Double): RaDec {
        val sinDec = sin(altAz.altRad) * sin(latRad) +
            cos(altAz.altRad) * cos(latRad) * cos(altAz.azRad)
        val dec = asin(sinDec.coerceIn(-1.0, 1.0))
        // Inverts raDecToAltAz exactly: from sinH = -sinAz*cosAlt/cosDec and
        // cosH = (sinAlt-sinDec*sinLat)/(cosDec*cosLat), multiply both by the
        // positive factor cosDec*cosLat so no division can blow up. The old
        // second argument mixed the azimuth convention and returned hour
        // angles off by up to hundreds of degrees.
        val ha = atan2(
            -sin(altAz.azRad) * cos(altAz.altRad) * cos(latRad),
            sin(altAz.altRad) - sinDec * sin(latRad),
        )
        val ra = (lstRad - ha).normalize2Pi()
        return RaDec(ra, dec)
    }

    fun raDecToEcliptic(raDec: RaDec, obliquityRad: Double): Ecliptic {
        val sinElat = sin(raDec.decRad) * cos(obliquityRad) -
            cos(raDec.decRad) * sin(obliquityRad) * sin(raDec.raRad)
        val eLat = asin(sinElat.coerceIn(-1.0, 1.0))
        val eLon = atan2(
            sin(raDec.raRad) * cos(obliquityRad) + tan(raDec.decRad) * sin(obliquityRad),
            cos(raDec.raRad),
        ).normalize2Pi()
        return Ecliptic(eLon, eLat)
    }

    fun eclipticToRaDec(ecliptic: Ecliptic, obliquityRad: Double): RaDec {
        val sinDec = sin(ecliptic.latRad) * cos(obliquityRad) +
            cos(ecliptic.latRad) * sin(obliquityRad) * sin(ecliptic.lonRad)
        val dec = asin(sinDec.coerceIn(-1.0, 1.0))
        val ra = atan2(
            sin(ecliptic.lonRad) * cos(obliquityRad) - tan(ecliptic.latRad) * sin(obliquityRad),
            cos(ecliptic.lonRad),
        ).normalize2Pi()
        return RaDec(ra, dec)
    }

    fun angularDistanceRad(a: RaDec, b: RaDec): Double {
        val sd = sin(a.decRad) * sin(b.decRad) + cos(a.decRad) * cos(b.decRad) * cos(a.raRad - b.raRad)
        return acos(sd.coerceIn(-1.0, 1.0))
    }

    // Native batch transform: marshal to an interleaved [ra,dec,...] DoubleArray,
    // transform in Rust, rebuild the List<AltAz>.
    fun batchRaDecToAltAz(radecs: List<RaDec>, lstRad: Double, latRad: Double): List<AltAz> {
        if (radecs.isEmpty()) return emptyList()
        // Same guard projectAll has: where the Rust library did not load
        // (an ABI the APK does not ship, e.g. x86_64), transform in Kotlin
        // rather than dying with UnsatisfiedLinkError.
        if (!com.vayunmathur.astronomy.domain.AstronomyNative.available) {
            return radecs.map { raDecToAltAz(it, lstRad, latRad) }
        }
        val input = DoubleArray(radecs.size * 2)
        for (i in radecs.indices) {
            input[2 * i] = radecs[i].raRad
            input[2 * i + 1] = radecs[i].decRad
        }
        val out = AstronomyNative.batchRaDecToAltAz(input, lstRad, latRad)
        return List(radecs.size) { i -> AltAz(out[2 * i], out[2 * i + 1]) }
    }

    fun atmosphericRefractionDeg(trueAltDeg: Double): Double {
        if (trueAltDeg < -1.0) return 0.0
        val alt = trueAltDeg.coerceAtLeast(0.0)
        return 1.02 / tan((alt + 10.3 / (alt + 5.11)).toRad()) / 60.0
    }
}
