package com.vayunmathur.astronomy.data.model

import androidx.annotation.StringRes
import com.vayunmathur.astronomy.R
import kotlinx.serialization.Serializable

/** A catalog star at J2000 equatorial coordinates, with magnitude and optional identity data. */
@Serializable
data class Star(
    val id: Int,
    val ra: Double, // radians J2000
    val dec: Double, // radians J2000
    val mag: Double,
    val bv: Double = 0.0,
    val name: String? = null,
    val properName: String? = null,
    val bayer: String? = null,
    val flamsteed: Int? = null,
    val constellation: String? = null,
    val distanceLy: Double? = null,
    val spectralClass: String? = null
)

/** A constellation identified by abbreviation, with stick-figure lines joining star ids. */
@Serializable
data class Constellation(
    val abbr: String,
    val name: String,
    val lines: List<List<Int>>
)

/** One alignment point tying a figure-image pixel to the catalog star it must overlay. */
@Serializable
data class ArtAnchor(
    val x: Int, // pixel x in the figure image
    val y: Int, // pixel y in the figure image
    val hip: Int // star (catalog id) this pixel should align to
)

/** A constellation figure image and its three anchors defining its sky placement. */
@Serializable
data class ConstellationArt(
    val abbr: String,
    val image: String, // asset filename under constellation_art/
    val anchors: List<ArtAnchor> // exactly 3, defines the affine placement
)

/** A deep-sky catalog entry (galaxy, nebula, or cluster) at J2000 coordinates. */
@Serializable
data class DeepSkyObject(
    val id: String,
    val name: String,
    val ra: Double,
    val dec: Double,
    val mag: Double,
    val type: String,
    val sizeArcmin: Double? = null,
    val constellation: String? = null
)

/** Keplerian orbital elements for a solar-system body, used to compute its position. */
@Serializable
data class OrbitalElements(
    val id: String,
    val name: String,
    val a: Double,
    val e: Double,
    val iDeg: Double,
    val omegaDeg: Double,
    val wDeg: Double,
    val lDeg: Double,
    val m0Deg: Double,
    val nDegPerDay: Double,
    val diameterKm: Double? = null,
    val colorArgb: Long? = null,
    val magBase: Double? = null
)

/** Serialized wrapper for the bundled star catalog. */
@Serializable
data class StarsCatalog(val stars: List<Star>)

/** Serialized wrapper for the bundled constellation catalog. */
@Serializable
data class ConstellationsCatalog(val constellations: List<Constellation>)

/** Serialized wrapper for the bundled constellation-art catalog. */
@Serializable
data class ConstellationArtCatalog(val art: List<ConstellationArt>)

/** Serialized wrapper for the bundled deep-sky catalog. */
@Serializable
data class DeepSkyCatalog(val objects: List<DeepSkyObject>)

/** Serialized wrapper for the bundled orbital-elements catalog. */
@Serializable
data class OrbitalElementsCatalog(val planets: List<OrbitalElements>)

/** Solar-system bodies shown as planets, each with its display-name string resource. */
enum class PlanetId(@StringRes val displayNameRes: Int) {
    SUN(R.string.planet_sun),
    MOON(R.string.planet_moon),
    MERCURY(R.string.planet_mercury),
    VENUS(R.string.planet_venus),
    MARS(R.string.planet_mars),
    JUPITER(R.string.planet_jupiter),
    SATURN(R.string.planet_saturn),
    URANUS(R.string.planet_uranus),
    NEPTUNE(R.string.planet_neptune)
}
