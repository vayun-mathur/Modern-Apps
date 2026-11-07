package com.vayunmathur.astronomy.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sanity tests for the built-in fallback catalogs: these are what the app
 * shows when the bundled JSON assets cannot be read, so their shape and
 * cross-references must hold on their own.
 */
class BuiltInCatalogsTest {

    @Test
    fun `brightest built-in star is Sirius and named stars come first`() {
        val stars = BuiltInCatalogs.stars
        assertEquals("Sirius", stars.first().properName)
        assertEquals(-1.44, stars.first().mag)
        // The named stars come first and are all bright.
        val named = stars.takeWhile { it.properName != null }
        assertTrue(named.size >= 20)
        assertTrue(named.all { it.mag < 3.0 })
        assertTrue(stars.all { it.ra in 0.0..(2 * Math.PI) })
        assertTrue(stars.all { it.dec in -Math.PI / 2..Math.PI / 2 })
    }

    @Test
    fun `filler stars are deterministic and complete the catalog`() {
        val stars = BuiltInCatalogs.stars
        val namedCount = stars.takeWhile { it.properName != null }.size
        assertEquals(namedCount + 4000, stars.size)
        val filler = stars.dropWhile { it.properName != null }
        assertTrue(filler.all { it.name == null && it.properName == null })
        assertEquals((100 until 4100).toList(), filler.map { it.id })
        assertTrue(filler.all { it.mag in 3.5..6.5 })
        // Unique ids across the whole catalog.
        assertEquals(stars.size, stars.map { it.id }.toSet().size)
    }

    @Test
    fun `constellation lines only reference built-in stars`() {
        val starIds = BuiltInCatalogs.stars.map { it.id }.toSet()
        assertTrue(BuiltInCatalogs.constellations.isNotEmpty())
        for (constellation in BuiltInCatalogs.constellations) {
            assertTrue(constellation.lines.isNotEmpty(), constellation.abbr)
            for (line in constellation.lines) {
                assertEquals(2, line.size)
                assertTrue(line.all { it in starIds }, "${constellation.abbr}: $line")
            }
        }
        assertTrue(BuiltInCatalogs.constellations.any { it.abbr == "Ori" && it.name == "Orion" })
    }

    @Test
    fun `messier fallback holds the bright showpieces`() {
        val byId = BuiltInCatalogs.messier.associateBy { it.id }
        assertEquals("Andromeda Galaxy", byId["M31"]?.name)
        assertEquals("Orion Nebula", byId["M42"]?.name)
        assertNotNull(byId["M45"])
        assertTrue(BuiltInCatalogs.messier.all { it.mag < 9.0 })
        assertTrue(BuiltInCatalogs.messier.all { it.ra in 0.0..(2 * Math.PI) })
    }

    @Test
    fun `orbital fallback has the sun earth and planets in order`() {
        val orbital = BuiltInCatalogs.orbital
        assertEquals("SUN", orbital.first().id)
        val earth = orbital.firstOrNull { it.id == "EARTH" }
        assertNotNull(earth)
        assertEquals(1.0, earth.a)
        val ids = orbital.map { it.id }
        for (planet in listOf("MERCURY", "VENUS", "MARS", "JUPITER", "SATURN")) {
            assertTrue(planet in ids, planet)
        }
        // Semi-major axes grow with distance from the Sun.
        val byId = orbital.associateBy { it.id }
        val solarOrder = listOf("MERCURY", "VENUS", "EARTH", "MARS", "JUPITER", "SATURN")
        val axes = solarOrder.map { byId.getValue(it).a }
        assertEquals(axes, axes.sorted())
        assertTrue(orbital.drop(1).all { it.e in 0.0..1.0 })
    }
}
