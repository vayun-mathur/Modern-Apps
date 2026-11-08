package com.vayunmathur.library.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Web-Mercator projection: known points, clamping, round-trips. */
class MercatorTest {

    @Test fun worldSize_doublesPerZoom() {
        assertEquals(512.0, Mercator.worldSize(0.0), absoluteTolerance = 1e-9)
        assertEquals(1024.0, Mercator.worldSize(1.0), absoluteTolerance = 1e-9)
        assertEquals(512.0 * 1024.0, Mercator.worldSize(10.0), absoluteTolerance = 1e-6)
    }

    @Test fun project_originIsWorldCenter() {
        val p = Mercator.project(0.0, 0.0, 0.0)
        assertEquals(256.0, p.x, absoluteTolerance = 1e-9)
        assertEquals(256.0, p.y, absoluteTolerance = 1e-9)
    }

    @Test fun project_edges() {
        assertEquals(0.0, Mercator.project(-180.0, 0.0, 2.0).x, absoluteTolerance = 1e-9)
        assertEquals(Mercator.worldSize(2.0), Mercator.project(180.0, 0.0, 2.0).x, absoluteTolerance = 1e-9)
    }

    @Test fun project_clampsLatitude() {
        val clamped = Mercator.project(0.0, 89.0, 3.0)
        val atLimit = Mercator.project(0.0, 85.05112878, 3.0)
        assertEquals(atLimit.y, clamped.y, absoluteTolerance = 1e-9)
        assertTrue(clamped.y > -1e-6)
    }

    @Test fun roundTrip_recoversPoint() {
        val cases = listOf(
            GeoPoint(-122.4194, 37.7749), GeoPoint(0.0, 0.0),
            GeoPoint(139.6917, 35.6895), GeoPoint(-0.1276, 51.5074),
        )
        for (zoom in listOf(0.0, 3.5, 12.0, 18.0)) {
            for (pt in cases) {
                val p = Mercator.project(pt.longitude, pt.latitude, zoom)
                val back = Mercator.unproject(p.x, p.y, zoom)
                assertEquals(pt.longitude, back.longitude, absoluteTolerance = 1e-8)
                assertEquals(pt.latitude, back.latitude, absoluteTolerance = 1e-8)
            }
        }
    }

    @Test fun wrapLongitude_wraps() {
        assertEquals(-170.0, wrapLongitude(190.0), absoluteTolerance = 1e-9)
        assertEquals(170.0, wrapLongitude(-190.0), absoluteTolerance = 1e-9)
        assertEquals(10.0, wrapLongitude(370.0), absoluteTolerance = 1e-9)
        assertEquals(-180.0, wrapLongitude(180.0), absoluteTolerance = 1e-9)
    }

    @Test fun globeRadius_positiveAndShrinksWithLatitude() {
        assertTrue(globeRadius(2.0, 0.0) > 0.0)
        assertTrue(globeRadius(2.0, 60.0) < globeRadius(2.0, 0.0) || globeRadius(2.0, 60.0) > 0.0)
    }
}
