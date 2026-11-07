package com.vayunmathur.astronomy.domain.projection

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.astronomy.domain.AstronomyNative
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.engine.toRad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge cases for the projection layer: mesh culling, centre handling, rotation, batch paths. */
class ProjectionEdgeCasesTest {

    @Test
    fun `mesh projection culls points beyond 175 degrees`() {
        val vs = ViewState(0.0, 0.0, 70f, 1080f, 1920f)
        val proj = StereographicProjection(vs)
        // Antipode of the centre is far past the mesh cutoff.
        assertNull(proj.projectMesh(AltAz(PI, 0.0)))
        // The centre itself projects to the middle of the screen.
        val centre = proj.projectMesh(AltAz(0.0, 0.0))
        assertNotNull(centre)
        assertEquals(540f, centre.x, 1f)
        assertEquals(960f, centre.y, 1f)
        // A nearby point projects near the centre.
        val near = proj.projectMesh(AltAz(5.0.toRad(), 5.0.toRad()))
        assertNotNull(near)
    }

    @Test
    fun `unproject of the screen centre is the view centre`() {
        val vs = ViewState(30.0.toRad(), 45.0.toRad(), 70f, 1080f, 1920f, rotationRad = 0.0)
        val proj = StereographicProjection(vs)
        val centre = proj.unproject(Offset(540f, 960f))
        assertNotNull(centre)
        assertTrue(abs(centre.azRad - 30.0.toRad()) < 1e-9)
        assertTrue(abs(centre.altRad - 45.0.toRad()) < 1e-9)
    }

    @Test
    fun `project then unproject round-trips off-centre with rotation`() {
        val vs = ViewState(100.0.toRad(), 20.0.toRad(), 90f, 800f, 600f, rotationRad = 0.4)
        val proj = StereographicProjection(vs)
        val orig = AltAz(110.0.toRad(), 30.0.toRad())
        val projected = proj.project(orig)
        assertNotNull(projected)
        val back = proj.unproject(projected)
        assertNotNull(back)
        assertTrue(abs(back.azRad - orig.azRad) < 0.002, "az ${back.azRad} vs ${orig.azRad}")
        assertTrue(abs(back.altRad - orig.altRad) < 0.002, "alt ${back.altRad} vs ${orig.altRad}")
    }

    @Test
    fun `projectAll of an empty list is empty`() {
        val vs = ViewState(0.0, 0.0, 70f, 1080f, 1920f)
        assertEquals(emptyList(), projectAll(vs, emptyList()))
    }

    @Test
    fun `projectAll matches one-by-one projection`() {
        val vs = ViewState(0.0, 45.0.toRad(), 70f, 1080f, 1920f)
        val points = listOf(
            AltAz(0.0, 45.0.toRad()),
            AltAz(10.0.toRad(), 50.0.toRad()),
            AltAz((-20.0).toRad(), 30.0.toRad()),
        )
        val batch = projectAll(vs, points)
        assertEquals(points.size, batch.size)
        val single = StereographicProjection(vs)
        points.forEachIndexed { index, point ->
            val expected = single.project(point)
            val actual = batch[index]
            if (expected == null) {
                // Native batch marks culled points NaN; the Kotlin path returns null.
                assertTrue(actual == null || actual.x.isNaN(), "index $index")
            } else {
                assertNotNull(actual)
                assertTrue(abs(actual.x - expected.x) < 2f, "index $index x ${actual.x} vs ${expected.x}")
                assertTrue(abs(actual.y - expected.y) < 2f, "index $index y ${actual.y} vs ${expected.y}")
            }
        }
    }

    @Test
    fun `native bridge availability is a stable answer`() {
        // On the unit-test host the Rust library is on java.library.path; in
        // Layoutlib it is not. Either way the flag must be readable and the
        // batch entry points must agree with it.
        val available = AstronomyNative.available
        if (available) {
            val out = AstronomyNative.batchRaDecToAltAz(
                doubleArrayOf(1.0, 0.5, 2.0, -0.2),
                lstRad = 1.0,
                latRad = 0.6,
            )
            assertEquals(4, out.size)
            assertTrue(out.all { it.isFinite() })
        }
    }
}
