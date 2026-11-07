package com.vayunmathur.astronomy.ui

import androidx.compose.ui.graphics.Color
import com.vayunmathur.astronomy.domain.engine.AltAz
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-helper tests for SkyArt: colour bands, radii, vectors and matrix inversion. */
class SkyArtHelpersTest {

    @Test
    fun `star radius shrinks with magnitude and clamps at both ends`() {
        assertEquals(5.5f, starRadius(-3.0), 0.001f)
        assertEquals(4.2f, starRadius(0.0), 0.001f)
        assertTrue(starRadius(3.0) < starRadius(1.0))
        assertEquals(0.9f, starRadius(10.0), 0.001f)
        assertEquals(0.9f, starRadius(100.0), 0.001f)
    }

    @Test
    fun `bv colour walks the temperature bands`() {
        assertEquals(Color(0xFF9BB0FF), bvToColor(-0.3))
        assertEquals(Color(0xFFC6D2FF), bvToColor(-0.2))
        assertEquals(Color(0xFFC6D2FF), bvToColor(-0.1))
        assertEquals(Color.White, bvToColor(0.0))
        assertEquals(Color.White, bvToColor(0.29))
        assertEquals(Color(0xFFFFFFE0), bvToColor(0.3))
        assertEquals(Color(0xFFFFE4B5), bvToColor(0.6))
        assertEquals(Color(0xFFFFA500), bvToColor(1.0))
        assertEquals(Color(0xFFFF6347), bvToColor(1.4))
        assertEquals(Color(0xFFFF6347), bvToColor(2.0))
    }

    @Test
    fun `planet colours are known for solar system bodies and white otherwise`() {
        assertEquals(Color(0xFFFF4500), planetColor("MARS"))
        assertEquals(Color(0xFFFFE0B0), planetColor("VENUS"))
        assertNotNull(planetColor("JUPITER"))
        assertNotNull(planetColor("SATURN"))
        assertEquals(Color.White, planetColor("PLUTO"))
        assertEquals(Color.White, planetColor(""))
    }

    @Test
    fun `alt-az to vector is a unit vector pointing the right way`() {
        val zenith = altAzToVec(AltAz(0.0, PI / 2))
        assertTrue(abs(zenith[2] - 1.0) < 1e-9)
        val north = altAzToVec(AltAz(0.0, 0.0))
        assertTrue(abs(north[0]) < 1e-9 && abs(north[1] - 1.0) < 1e-9 && abs(north[2]) < 1e-9)
        val east = altAzToVec(AltAz(PI / 2, 0.0))
        assertTrue(abs(east[0] - 1.0) < 1e-9 && abs(east[1]) < 1e-9)
        val arbitrary = altAzToVec(AltAz(1.1, 0.4))
        val len = kotlin.math.sqrt(arbitrary.sumOf { it * it })
        assertTrue(abs(len - 1.0) < 1e-9)
    }

    @Test
    fun `invert3x3 inverts a well-formed matrix and rejects a singular one`() {
        // Identity inverts to itself.
        val identity = invert3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        assertNotNull(identity)
        assertTrue(identity.contentEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
        // Rows linearly dependent (anchor pixels collinear) -> singular.
        assertNull(invert3x3(0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 1.0))
        assertNull(invert3x3(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
        // A scaled matrix inverts to the reciprocal scale.
        val scaled = invert3x3(2.0, 0.0, 0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 5.0)
        assertNotNull(scaled)
        assertEquals(0.5, scaled[0], 1e-9)
        assertEquals(0.25, scaled[4], 1e-9)
        assertEquals(0.2, scaled[8], 1e-9)
    }

    @Test
    fun `art mesh constant is positive`() {
        assertTrue(ART_MESH > 0)
    }
}
