package com.vayunmathur.library.map

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Renderer mutators with no surface attached: state changes and wakes, no JNI. */
class RendererStateTest {

    private fun renderer() = SurfaceMapRenderer(Application(), density = 3f)

    @Test fun defaults_initialising() {
        val r = renderer()
        assertEquals(MapRenderState.Initialising, r.renderState)
        assertEquals(CameraPosition(), r.camera)
        assertFalse(r.renderFrame())
        assertEquals(0L, r.pickAt(10f, 10f))
        assertTrue(r.pickLabels(DpRect(0.dp, 0.dp, 10.dp, 10.dp), emptySet()).isEmpty())
    }

    @Test fun setters_storeAndWake() {
        val r = renderer()
        val wakes = r.wakeCount
        r.setPalette(dark = true, muted = true)
        r.setLayers(LayerOptions(poi = true, transit = true))
        r.setOnline(false)
        r.setGestureActive(true, 1.5f)
        r.setUserPuck(UserPuck(GeoPoint(1.0, 2.0), bearing = 45f))
        r.setMarkers(listOf(MapMarker(1L, GeoPoint(0.0, 0.0), MarkerIcon.SEARCH)))
        r.setVehicles(listOf(MapMarker(2L, GeoPoint(1.0, 1.0), MarkerIcon.VEHICLE_BUS)))
        r.setRegionMask(42L)
        r.setRegionMask(null)
        r.setRoute(listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0)))
        r.setRoute(RouteOverlay(listOf(RouteSegment(listOf(GeoPoint(0.0, 0.0)), Color.Red))))
        r.setRoute(null as RouteOverlay?)
        r.setTrafficSpeeds(longArrayOf(1L), intArrayOf(0xFF0000))
        r.clearTraffic()
        r.camera = CameraPosition(GeoPoint(10.0, 20.0), 12.0, bearing = 90.0, pitch = 30.0)
        assertEquals(90.0, r.camera.bearing, absoluteTolerance = 1e-9)
        assertTrue(r.wakeCount > wakes, "mutators must wake the frame loop")
    }

    @Test fun moonAndLifecycle_noSurface() {
        val r = renderer()
        r.setMoonTextures(ByteArray(4), 1, 1, ByteArray(2), 1, 1)
        r.clearMoonTextures()
        r.globeEnabled = true; r.body = MapBody.Moon
        assertTrue(r.globeEnabled)
        r.tileSource = TileSource.LocalFile
        assertEquals(TileSource.LocalFile, r.tileSource)
        r.trimMemory(20)
        r.resize(100, 200)
        r.detachSurface()
        r.stop()
        r.destroy()
    }

    @Test fun moonTextures_contentEquality() {
        val a = MoonTextures(byteArrayOf(1, 2), 1, 1, byteArrayOf(3), 1, 1)
        val b = MoonTextures(byteArrayOf(1, 2), 1, 1, byteArrayOf(3), 1, 1)
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode())
    }
}
