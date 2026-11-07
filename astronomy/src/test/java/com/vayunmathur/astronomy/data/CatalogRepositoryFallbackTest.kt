package com.vayunmathur.astronomy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fallback tests for [CatalogRepository] through its asset-loader seam:
 * missing assets and corrupt JSON both fall back to the built-in catalogs,
 * while well-formed asset JSON (catalog-shaped or bare-list) is honoured.
 */
@RunWith(AndroidJUnit4::class)
class CatalogRepositoryFallbackTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `missing assets fall back to the built-in catalogs`() {
        runBlocking {
        val repo = CatalogRepository(context) { null }
        repo.loadAll()
        assertEquals(BuiltInCatalogs.stars, repo.stars)
        assertEquals(BuiltInCatalogs.constellations, repo.constellations)
        assertEquals(BuiltInCatalogs.messier, repo.deepSky)
        assertEquals(BuiltInCatalogs.orbital, repo.planets)
        assertEquals("EARTH", repo.earth?.id)
        // Constellation art has no built-in fallback.
        assertTrue(repo.constellationArt.isEmpty())
        }
    }

    @Test
    fun `corrupt assets fall back to the built-in catalogs`() {
        runBlocking {
        val repo = CatalogRepository(context) { "this is not json {{{" }
        repo.loadAll()
        assertEquals(BuiltInCatalogs.stars, repo.stars)
        assertEquals(BuiltInCatalogs.constellations, repo.constellations)
        assertEquals(BuiltInCatalogs.messier, repo.deepSky)
        assertEquals(BuiltInCatalogs.orbital, repo.planets)
        assertTrue(repo.constellationArt.isEmpty())
        }
    }

    @Test
    fun `well-formed catalog-shaped assets are honoured`() {
        runBlocking {
        val assets = mapOf(
            "catalog/stars.json" to """{"stars":[{"id":42,"ra":1.0,"dec":0.5,"mag":2.0,"properName":"Answer"}]}""",
            "catalog/constellations.json" to """{"constellations":[{"abbr":"Tst","name":"Test","lines":[[42,42]]}]}""",
            "catalog/constellation_art.json" to """{"art":[{"abbr":"Tst","image":"tst.png","anchors":[{"x":1,"y":2,"hip":42}]}]}""",
            "catalog/messier.json" to """{"objects":[{"id":"M99","name":"Test Galaxy","ra":1.0,"dec":0.5,"mag":5.0,"type":"galaxy"}]}""",
            "catalog/orbital_elements.json" to """{"planets":[{"id":"EARTH","name":"Earth","a":1.0,"e":0.0,"iDeg":0.0,"omegaDeg":0.0,"wDeg":0.0,"lDeg":0.0,"m0Deg":0.0,"nDegPerDay":1.0}]}""",
        )
        val repo = CatalogRepository(context) { path -> assets[path] }
        repo.loadAll()
        assertEquals(listOf(42), repo.stars.map { it.id })
        assertEquals("Answer", repo.stars.single().properName)
        assertEquals("Tst", repo.constellations.single().abbr)
        assertEquals("tst.png", repo.constellationArt.single().image)
        assertEquals(listOf("M99"), repo.deepSky.map { it.id })
        assertEquals("EARTH", repo.earth?.id)
        assertEquals(1, repo.planets.size)
        }
    }

    @Test
    fun `bare-list asset shapes are honoured`() {
        runBlocking {
        val assets = mapOf(
            "catalog/stars.json" to """[{"id":7,"ra":0.1,"dec":0.2,"mag":3.0}]""",
            "catalog/constellations.json" to """[{"abbr":"Lst","name":"List","lines":[]}]""",
            "catalog/constellation_art.json" to """[{"abbr":"Lst","image":"l.png","anchors":[]}]""",
            "catalog/messier.json" to """[{"id":"M1","name":"Crab","ra":1.4,"dec":0.38,"mag":8.4,"type":"nebula"}]""",
            "catalog/orbital_elements.json" to """[{"id":"MARS","name":"Mars","a":1.52,"e":0.09,"iDeg":1.85,"omegaDeg":49.5,"wDeg":286.4,"lDeg":355.4,"m0Deg":355.4,"nDegPerDay":0.524}]""",
        )
        val repo = CatalogRepository(context) { path -> assets[path] }
        repo.loadAll()
        assertEquals(listOf(7), repo.stars.map { it.id })
        assertEquals("Lst", repo.constellations.single().abbr)
        assertEquals("l.png", repo.constellationArt.single().image)
        assertEquals(listOf("M1"), repo.deepSky.map { it.id })
        assertEquals(listOf("MARS"), repo.planets.map { it.id })
        // No EARTH in this orbital list.
        assertEquals(null, repo.earth)
        assertNotNull(repo.stars.single())
        }
    }
}
