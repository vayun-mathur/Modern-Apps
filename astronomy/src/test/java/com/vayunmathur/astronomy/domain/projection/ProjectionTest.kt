package com.vayunmathur.astronomy.domain.projection

import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.engine.toRad
import kotlin.math.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectionTest {

    @Test
    fun stereographic_center_projects_to_center() {
        val vs = ViewState(0.0, (45.0).toRad(), 70f, 1080f, 1920f)
        val proj = StereographicProjection(vs)
        val center = AltAz(0.0, (45.0).toRad())
        val off = proj.project(center)
        assertNotNull(off)
        assertTrue(abs(off.x - vs.screenW / 2) < 1f)
        assertTrue(abs(off.y - vs.screenH / 2) < 1f)
    }

    @Test
    fun stereographic_invertibility() {
        val vs = ViewState((180.0).toRad(), (60.0).toRad(), 80f, 1080f, 1920f)
        val proj = StereographicProjection(vs)
        val points = listOf(
            AltAz((180.0).toRad(), (70.0).toRad()),
            AltAz((200.0).toRad(), (50.0).toRad()),
            AltAz((160.0).toRad(), (30.0).toRad())
        )
        points.forEach { orig ->
            val off = proj.project(orig) ?: return@forEach // may be invisible
            val back = proj.unproject(off)
            assertNotNull(back)
            val dAz = ((orig.azRad - back!!.azRad + PI) % (2*PI) - PI)
            val dAlt = orig.altRad - back.altRad
            // 0.5 deg tolerance
            assertTrue(abs(dAz) < 0.01 && abs(dAlt) < 0.01, "invertibility failed az $dAz alt $dAlt orig $orig back $back")
        }
    }

    @Test
    fun stereographic_horizon_culling() {
        val vs = ViewState(0.0, (30.0).toRad(), 60f, 1080f, 1920f)
        val proj = StereographicProjection(vs)
        val below = AltAz(0.0, (-5.0).toRad())
        assertTrue(!proj.isVisible(below))
        assertTrue(proj.project(below) == null)
    }

    @Test
    fun stereographic_fov_scaling() {
        val narrow = ViewState(0.0, (45.0).toRad(), 20f, 1080f, 1920f)
        val wide = ViewState(0.0, (45.0).toRad(), 100f, 1080f, 1920f)
        val projNarrow = StereographicProjection(narrow)
        val projWide = StereographicProjection(wide)
        val pt = AltAz((5.0).toRad(), (45.0).toRad())
        val offN = projNarrow.project(pt)
        val offW = projWide.project(pt)
        if (offN != null && offW != null) {
            // narrow FOV should place same angular offset farther from center
            val distN = hypot(offN.x - narrow.screenW/2, offN.y - narrow.screenH/2)
            val distW = hypot(offW.x - wide.screenW/2, offW.y - wide.screenH/2)
            assertTrue(distN > distW, "narrow $distN should > wide $distW")
        }
    }
}
