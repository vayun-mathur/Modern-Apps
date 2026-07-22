package com.vayunmathur.astronomy.data.model

import kotlinx.serialization.Serializable

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

@Serializable
data class Constellation(
    val abbr: String,
    val name: String,
    val lines: List<List<Int>>
)

@Serializable
data class ArtAnchor(
    val x: Int, // pixel x in the figure image
    val y: Int, // pixel y in the figure image
    val hip: Int // star (catalog id) this pixel should align to
)

@Serializable
data class ConstellationArt(
    val abbr: String,
    val image: String, // asset filename under constellation_art/
    val anchors: List<ArtAnchor> // exactly 3, defines the affine placement
)

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

@Serializable
data class StarsCatalog(val stars: List<Star>)

@Serializable
data class ConstellationsCatalog(val constellations: List<Constellation>)

@Serializable
data class ConstellationArtCatalog(val art: List<ConstellationArt>)

@Serializable
data class DeepSkyCatalog(val objects: List<DeepSkyObject>)

@Serializable
data class OrbitalElementsCatalog(val planets: List<OrbitalElements>)

enum class PlanetId(val displayName: String) {
    SUN("Sun"),
    MOON("Moon"),
    MERCURY("Mercury"),
    VENUS("Venus"),
    MARS("Mars"),
    JUPITER("Jupiter"),
    SATURN("Saturn"),
    URANUS("Uranus"),
    NEPTUNE("Neptune")
}
