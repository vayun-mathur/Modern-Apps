package com.vayunmathur.astronomy.data

import android.content.Context
import com.vayunmathur.astronomy.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class CatalogRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    var stars: List<Star> = emptyList(); private set
    var constellations: List<Constellation> = emptyList(); private set
    var constellationArt: List<ConstellationArt> = emptyList(); private set
    var deepSky: List<DeepSkyObject> = emptyList(); private set
    var planets: List<OrbitalElements> = emptyList(); private set
    var earth: OrbitalElements? = null; private set
    @Volatile private var loaded = false

    suspend fun loadAll() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        stars = loadStars()
        constellations = loadConstellations()
        constellationArt = loadConstellationArt()
        deepSky = loadDeepSky()
        val orbital = loadOrbital()
        planets = orbital.planets
        earth = planets.firstOrNull { it.id == "EARTH" }
        loaded = true
    }

    private fun loadJsonAsset(path: String): String? = try { context.assets.open(path).bufferedReader().use { it.readText() } } catch (_: Exception) { null }

    private fun loadStars(): List<Star> {
        val text = loadJsonAsset("catalog/stars.json") ?: return BuiltInCatalogs.stars
        return try { json.decodeFromString<StarsCatalog>(text).stars } catch (_: Exception) {
            try { json.decodeFromString<List<Star>>(text) } catch (_: Exception) { BuiltInCatalogs.stars }
        }
    }
    private fun loadConstellations(): List<Constellation> {
        val text = loadJsonAsset("catalog/constellations.json") ?: return BuiltInCatalogs.constellations
        return try { json.decodeFromString<ConstellationsCatalog>(text).constellations } catch (_: Exception) {
            try { json.decodeFromString<List<Constellation>>(text) } catch (_: Exception) { BuiltInCatalogs.constellations }
        }
    }
    private fun loadConstellationArt(): List<ConstellationArt> {
        val text = loadJsonAsset("catalog/constellation_art.json") ?: return emptyList()
        return try { json.decodeFromString<ConstellationArtCatalog>(text).art } catch (_: Exception) {
            try { json.decodeFromString<List<ConstellationArt>>(text) } catch (_: Exception) { emptyList() }
        }
    }
    private fun loadDeepSky(): List<DeepSkyObject> {
        val text = loadJsonAsset("catalog/messier.json") ?: return BuiltInCatalogs.messier
        return try { json.decodeFromString<DeepSkyCatalog>(text).objects } catch (_: Exception) {
            try { json.decodeFromString<List<DeepSkyObject>>(text) } catch (_: Exception) { BuiltInCatalogs.messier }
        }
    }
    private fun loadOrbital(): OrbitalElementsCatalog {
        val text = loadJsonAsset("catalog/orbital_elements.json")
        if (text != null) {
            try { return json.decodeFromString<OrbitalElementsCatalog>(text) } catch (_: Exception) {}
            try { return OrbitalElementsCatalog(json.decodeFromString<List<OrbitalElements>>(text)) } catch (_: Exception) {}
        }
        return OrbitalElementsCatalog(BuiltInCatalogs.orbital)
    }
}

object BuiltInCatalogs {
    val stars: List<Star> = listOf(
        Star(1, 2.0632318, 0.68092, -1.44, 0.0, "Sirius", "Sirius", "Alpha", null, "CMa", 8.6, "A1V"),
        Star(2, 0.94511, 0.08153, -0.62, 0.0, "Canopus", "Canopus", "Alpha", null, "Car", 310.0, "A9 II"),
        Star(3, 2.011977, 1.38542, -0.05, 0.01, "Arcturus", "Arcturus", "Alpha", null, "Boo", 36.7, "K0 III"),
        Star(4, 4.904635, 1.3475, -0.05, 0.15, "Vega", "Vega", "Alpha", null, "Lyr", 25.0, "A0 Va"),
        Star(5, 3.733777, 1.1333904, 0.03, 0.15, "Capella", "Capella", "Alpha", null, "Aur", 42.9, "G3 III"),
        Star(6, 1.5941598, -0.303976, 0.08, 0.0, "Rigel", "Rigel", "Beta", null, "Ori", 860.0, "B8 Ia"),
        Star(7, 5.53395, -1.0000529, 0.12, 0.0, "Procyon", "Procyon", "Alpha", null, "CMi", 11.46, "F5 IV-V"),
        Star(8, 1.5045678, 0.5948826, 0.42, 0.0, "Betelgeuse", "Betelgeuse", "Alpha", null, "Ori", 642.5, "M1-M2 Ia-Iab"),
        Star(9, 3.179167, 0.124354, 0.42, -0.21, "Achernar", "Achernar", "Alpha", null, "Eri", 139.0, "B6 Vep"),
        Star(10, 2.1443926, 0.0778795, 0.50, 0.14, "Hadar", "Agena", "Beta", null, "Cen", 390.0, "B1 III"),
        Star(11, 1.5161099, 0.094466, 0.61, 0.0, "Altair", "Altair", "Alpha", null, "Aql", 16.73, "A7 V"),
        Star(12, 4.512539, 1.0303768, 0.76, 0.07, "Aldebaran", "Aldebaran", "Alpha", null, "Tau", 65.0, "K5 III"),
        Star(13, 5.2422002, -0.0150372, 0.77, 0.39, "Antares", "Antares", "Alpha", 21, "Sco", 550.0, "M1.5 Iab-b"),
        Star(14, 2.441246, -0.7140256, 0.79, 0.14, "Acrux", "Acrux", "Alpha", null, "Cru", 321.0, "B0.5 IV"),
        Star(15, 1.92341299, 1.56207, 0.87, -0.04, "Spica", "Spica", "Alpha", 67, "Vir", 250.0, "B1 III-IV"),
        Star(16, 3.0028855, 1.23141, 0.97, 0.0, "Pollux", "Pollux", "Beta", 78, "Gem", 33.78, "K0 III"),
        Star(17, 5.9196997, 0.95444583, 1.06, 1.48, "Fomalhaut", "Fomalhaut", "Alpha", null, "PsA", 25.13, "A4 V"),
        Star(18, 4.4266224, -0.41680044, 1.14, 1.42, "Mimosa", "Becrux", "Beta", null, "Cru", 280.0, "B0.5 III"),
        Star(19, 5.03644, 0.27895867, 1.16, 0.09, "Deneb", "Deneb", "Alpha", 50, "Cyg", 2600.0, "A2 Ia"),
        Star(20, 0.66808675, 1.5575598, 1.98, 0.0, "Polaris", "Polaris", "Alpha", 1, "UMi", 433.0, "F7 Ib"),
        Star(21, 3.366, 1.29, 2.9, 0.0, "Dubhe", "Dubhe", "Alpha", 50, "UMa", 123.0, "F7"),
        Star(22, 4.76, 1.21, 2.7, 0.0, "Schedar", "Schedar", "Alpha", 18, "Cas", 228.0, "K0 IIIa"),
        Star(23, 0.26, 1.03, 2.2, 0.0, "Caph", "Caph", "Beta", 8, "Cas", 54.0, "F2 III-IV"),
        Star(24, 5.919, -0.6, 2.1, 0.0, "Nunki", "Nunki", "Sigma", 0, "Sgr", 228.0, "B2.5 V"),
        Star(25, 2.06, 0.34, 0.4, 0.0, "Rigil Kentaurus", "Rigil Kentaurus", "Alpha", null, "Cen", 4.37, "G2 V"),
        Star(26, 4.06, 0.42, 0.77, 0.0, "Castor", "Castor", "Alpha", 66, "Gem", 52.0, "A1 V"),
        Star(27, 2.3898913, 1.0427, 1.25, 0.0, "Beta Crucis", "Mimosa", "Beta", null, "Cru", 280.0, "B0.5 III"),
    ) + generateFillerStars()

    private fun generateFillerStars(): List<Star> {
        val rnd = java.util.Random(42)
        val list = mutableListOf<Star>()
        var id = 100
        for (i in 0 until 4000) {
            val ra = rnd.nextDouble() * 2 * Math.PI
            val sinDec = rnd.nextDouble() * 2 - 1
            val dec = kotlin.math.asin(sinDec)
            val mag = 3.5 + rnd.nextDouble() * 3.0
            val bv = rnd.nextDouble() * 2 - 0.5
            list.add(Star(id++, ra, dec, mag, bv, null, null, null, null, null, null, null))
        }
        return list
    }

    val constellations: List<Constellation> = listOf(
        Constellation("UMa", "Ursa Major", listOf(listOf(21,22), listOf(22,23))),
        Constellation("UMi", "Ursa Minor", listOf(listOf(20,21))),
        Constellation("Ori", "Orion", listOf(listOf(8,6), listOf(6,1))),
        Constellation("Cas", "Cassiopeia", listOf(listOf(22,23))),
        Constellation("Sco", "Scorpius", listOf(listOf(13,24))),
        Constellation("Cru", "Crux", listOf(listOf(14,27))),
        Constellation("Lyr", "Lyra", listOf(listOf(4,19))),
        Constellation("Gem", "Gemini", listOf(listOf(16,26))),
        Constellation("Cen", "Centaurus", listOf(listOf(25,14))),
    )

    val messier: List<DeepSkyObject> = listOf(
        DeepSkyObject("M31", "Andromeda Galaxy", 0.185*2*Math.PI, 0.712, 3.4, "galaxy", 178.0, "And"),
        DeepSkyObject("M42", "Orion Nebula", 1.459, -0.093, 4.0, "nebula", 65.0, "Ori"),
        DeepSkyObject("M45", "Pleiades", 0.99, 0.42, 1.6, "cluster", 110.0, "Tau"),
        DeepSkyObject("M13", "Hercules Cluster", 4.30, 0.631, 5.8, "cluster", 20.0, "Her"),
        DeepSkyObject("M51", "Whirlpool Galaxy", 3.47, 0.82, 8.4, "galaxy", 11.0, "CVn"),
        DeepSkyObject("M57", "Ring Nebula", 4.866, 0.576, 8.8, "nebula", 1.5, "Lyr"),
    )

    val orbital: List<OrbitalElements> = listOf(
        OrbitalElements("SUN", "Sun", 0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,null,0xFFFFD700,-26.7),
        OrbitalElements("EARTH", "Earth", 1.0,0.0167086,0.0,-11.26064,102.9372,100.46435,100.46435,0.9856091,null,0xFF00FF00,-3.0),
        OrbitalElements("MERCURY", "Mercury",0.38709893,0.20563069,7.00487,48.33167,29.12478,174.875,174.875,4.09233445,4879.0,0xFFA9A9A9,-0.42),
        OrbitalElements("VENUS", "Venus",0.72333199,0.00677323,3.39471,76.68069,54.88378,181.975,181.975,1.60213034,12104.0,0xFFFFE0B0,-4.4),
        OrbitalElements("MARS", "Mars",1.52366231,0.09341233,1.85061,49.57854,286.4623,355.453,355.453,0.52402068,6792.0,0xFFFF4500,-0.5),
        OrbitalElements("JUPITER", "Jupiter",5.20336301,0.04839266,1.30530,100.55615,273.8777,34.40438,20.0202,0.08308529,139820.0,0xFFFFDAB9,-9.4),
        OrbitalElements("SATURN", "Saturn",9.53707032,0.05386179,2.48446,113.71504,339.3939,49.94432,317.020,0.03344414,116460.0,0xFFF0E68C,-8.9),
        OrbitalElements("URANUS", "Uranus",19.19126393,0.04725744,0.76986,74.22988,96.73436,313.23218,142.2386,0.01172834,50724.0,0xFFADD8E6,-7.1),
        OrbitalElements("NEPTUNE", "Neptune",30.06896348,0.00859048,1.76917,131.72169,272.8461,304.88003,256.228,0.00598103,49244.0,0xFF4169E1,-6.9),
    )
}
