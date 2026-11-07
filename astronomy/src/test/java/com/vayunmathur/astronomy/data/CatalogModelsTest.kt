package com.vayunmathur.astronomy.data

import com.vayunmathur.astronomy.data.model.OrbitalElements
import com.vayunmathur.astronomy.data.model.PlanetId
import com.vayunmathur.astronomy.data.model.Star
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Model tests: planet ids, orbital element defaults and star equality. */
class CatalogModelsTest {

    @Test
    fun `planet ids cover the sun moon and eight planets with resources`() {
        assertEquals(9, PlanetId.entries.size)
        assertEquals(PlanetId.SUN, PlanetId.valueOf("SUN"))
        assertEquals(PlanetId.NEPTUNE, PlanetId.valueOf("NEPTUNE"))
        assertTrue(PlanetId.entries.all { it.displayNameRes != 0 })
        assertEquals(
            PlanetId.entries.map { it.name },
            PlanetId.entries.map { it.toString() },
        )
    }

    @Test
    fun `orbital elements default the optional fields and copy cleanly`() {
        val mars = OrbitalElements("MARS", "Mars", 1.52, 0.09, 1.85, 49.5, 286.4, 355.4, 355.4, 0.524)
        assertEquals(null, mars.diameterKm)
        assertEquals(null, mars.colorArgb)
        assertEquals(null, mars.magBase)
        val bigger = mars.copy(diameterKm = 6779.0)
        assertEquals(6779.0, bigger.diameterKm)
        assertEquals(mars.a, bigger.a)
        assertNotEquals(mars, bigger)
        assertEquals(mars, mars.copy())
        assertEquals(mars.hashCode(), mars.copy().hashCode())
    }

    @Test
    fun `star defaults and component accessors`() {
        val star = Star(id = 5, ra = 1.0, dec = 0.5, mag = 2.0)
        assertEquals(0.0, star.bv)
        assertEquals(null, star.name)
        assertEquals(null, star.constellation)
        val (id, ra, dec, mag) = star
        assertEquals(5, id)
        assertEquals(1.0, ra)
        assertEquals(0.5, dec)
        assertEquals(2.0, mag)
        assertTrue(star.toString().contains("5"))
    }
}
