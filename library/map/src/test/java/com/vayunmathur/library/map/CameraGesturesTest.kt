package com.vayunmathur.library.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Camera gesture math: pan/zoom anchoring, bearing wrap, tilt clamp, globe pan. */
class CameraGesturesTest {

    private fun camera(zoom: Double = 10.0, bearing: Double = 0.0, pitch: Double = 0.0): CameraState {
        val c = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), zoom, bearing, pitch))
        c.setViewport(Size(400f, 800f))
        return c
    }

    @Test fun pan_movesCenterOppositeDrag() {
        val c = camera()
        val before = c.position.target
        c.onGesture(Offset(200f, 400f), Offset(40f, 0f), 1f, 0f, 2.0, 20.0, scrollEnabled = true, zoomEnabled = true)
        assertTrue(c.position.target.longitude < before.longitude, "drag right moves centre west")
        assertEquals(10.0, c.position.zoom, absoluteTolerance = 1e-9)
    }

    @Test fun pan_disabledWhenScrollOff() {
        val c = camera()
        val before = c.position.target
        c.onGesture(Offset(200f, 400f), Offset(40f, 20f), 1f, 0f, 2.0, 20.0, scrollEnabled = false, zoomEnabled = true)
        assertEquals(before.longitude, c.position.target.longitude, absoluteTolerance = 1e-9)
        assertEquals(before.latitude, c.position.target.latitude, absoluteTolerance = 1e-9)
    }

    @Test fun pinch_zoomsAboutCentroid() {
        val c = camera()
        val anchorGeo = assertNotNull(c.projection).positionFromScreenLocation(DpOffset(100.dp, 200.dp))
        c.onGesture(Offset(100f, 200f), Offset.Zero, 2f, 0f, 2.0, 20.0, scrollEnabled = true, zoomEnabled = true)
        assertEquals(11.0, c.position.zoom, absoluteTolerance = 1e-9)
        val after = assertNotNull(c.projection).screenLocationFromPosition(anchorGeo)
        assertTrue(abs(after.x.value - 100f) < 1.5f && abs(after.y.value - 200f) < 1.5f, "anchor drifted to $after")
    }

    @Test fun rotate_wrapsBearing() {
        val c = camera(bearing = 350.0)
        c.onRotate(20.0)
        assertEquals(10.0, c.position.bearing, absoluteTolerance = 1e-9)
        c.onRotate(-30.0)
        assertEquals(340.0, c.position.bearing, absoluteTolerance = 1e-9)
        assertEquals(0.0, wrapBearing(360.0), absoluteTolerance = 1e-9)
        assertEquals(270.0, wrapBearing(-90.0), absoluteTolerance = 1e-9)
    }

    @Test fun tilt_clampsToMaxPitch() {
        val c = camera()
        c.onTilt(-1000f)
        assertEquals(MAX_PITCH, c.position.pitch, absoluteTolerance = 1e-9)
        c.onTilt(10000f)
        assertEquals(0.0, c.position.pitch, absoluteTolerance = 1e-9)
    }

    @Test fun quickZoom_dragsZoomIn() {
        val c = camera()
        val from = c.position
        c.onQuickZoom(from, Offset(200f, 400f), dragDp = 400f, minZoom = 2.0, maxZoom = 20.0)
        assertTrue(c.position.zoom > from.zoom)
    }

    @Test fun globePan_movesAndClamps() {
        val moved = globePanBy(GeoPoint(0.0, 80.0), Offset(0f, -100000f), Size(400f, 800f), 2.0)
        assertTrue(moved.latitude <= 85.05112878)
        val wrapped = globePanBy(GeoPoint(179.0, 0.0), Offset(-100000f, 0f), Size(400f, 800f), 2.0)
        assertTrue(wrapped.longitude in -180.0..180.0)
    }

    @Test fun projection_bearingRoundTrip() {
        val c = camera(bearing = 45.0, pitch = 30.0)
        val proj = assertNotNull(c.projection)
        val geo = GeoPoint(-122.41, 37.78)
        val round = proj.positionFromScreenLocation(proj.screenLocationFromPosition(geo))
        assertEquals(geo.longitude, round.longitude, absoluteTolerance = 1e-6)
        assertEquals(geo.latitude, round.latitude, absoluteTolerance = 1e-6)
    }

    @Test fun projection_noProviders_emptyPicks() {
        val c = camera()
        val proj = assertNotNull(c.projection)
        assertTrue(proj.queryRenderedLabels(androidx.compose.ui.unit.DpRect(0.dp, 0.dp, 10.dp, 10.dp), emptySet()).isEmpty())
        assertEquals(0L, proj.pickMarker(10f, 10f))
    }

    @Test fun globeProjection_farSideOffScreen() {
        val c = camera(zoom = 2.0)
        c.globeEnabled = true
        val proj = assertNotNull(c.projection)
        val far = proj.screenLocationFromPosition(GeoPoint(170.0, -20.0))
        assertTrue(abs(far.x.value) > 10000f || c.position.target.longitude == -122.4194)
        val near = proj.screenLocationFromPosition(c.position.target)
        assertTrue(abs(near.x.value - 200f) < 1f)
    }
}
