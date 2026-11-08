package com.vayunmathur.library.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Value types: defaults, presets, content equality, level bands. */
class MapOptionsTest {

    @Test fun geoPointAndBounds_equality() {
        assertEquals(GeoPoint(1.0, 2.0), GeoPoint(1.0, 2.0))
        assertNotEquals(GeoPoint(1.0, 2.0), GeoPoint(2.0, 1.0))
        assertEquals(GeoBounds(-1.0, -2.0, 1.0, 2.0).copy(east = 3.0).east, 3.0)
    }

    @Test fun gesturePresets() {
        assertTrue(GestureOptions().isScrollEnabled)
        assertFalse(GestureOptions.RotationLocked.isRotateEnabled)
        assertTrue(GestureOptions.RotationLocked.isScrollEnabled)
        assertFalse(GestureOptions.TiltLocked.isTiltEnabled)
        assertFalse(GestureOptions.AllDisabled.isZoomEnabled)
        assertTrue(GestureOptions.AllDisabled.isTransformDisabled)
        assertFalse(GestureOptions().isTransformDisabled)
        assertTrue(GestureOptions.AllDisabled.isTiltDisabled)
        assertFalse(GestureOptions().isTiltDisabled)
    }

    @Test fun mapOptions_defaults() {
        val o = MapOptions()
        assertEquals(TileSource.Server, o.tileSource)
        assertTrue(o.ornamentOptions.isAttributionEnabled)
        assertFalse(OrnamentOptions.AllDisabled.isAttributionEnabled)
        assertEquals(MapBody.Earth, MapBody.valueOf("Earth"))
        assertEquals(2, MapStyle.entries.size)
        assertEquals(LayerOptions(), o.layerOptions)
    }

    @Test fun trafficTable_contentEqualityAndEmpty() {
        val a = TrafficColorTable(longArrayOf(1L, 2L), intArrayOf(10, 20))
        val b = TrafficColorTable(longArrayOf(1L, 2L), intArrayOf(10, 20))
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, TrafficColorTable(longArrayOf(1L), intArrayOf(10)))
        assertTrue(TrafficColorTable(longArrayOf(), intArrayOf()).isEmpty())
        assertFalse(a.isEmpty())
    }

    @Test fun regionLevel_bands() {
        assertEquals(1, RegionLevel.COUNTRY.min); assertEquals(2, RegionLevel.COUNTRY.max)
        assertEquals(3, RegionLevel.REGION.min); assertEquals(4, RegionLevel.REGION.max)
        assertEquals(7, RegionLevel.LOCALITY.min); assertEquals(12, RegionLevel.LOCALITY.max)
    }

    @Test fun overlayValues_roundTripFields() {
        val seg = RouteSegment(points = listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0)), color = androidx.compose.ui.graphics.Color.Red)
        val route = RouteOverlay(segments = listOf(seg))
        assertEquals(2, route.segments[0].points.size)
        val marker = MapMarker(id = 7L, position = GeoPoint(1.0, 2.0), icon = MarkerIcon.SEARCH, label = "Pin")
        assertEquals(7L, marker.id); assertEquals("Pin", marker.label)
        assertEquals(MarkerIcon.PARKING, 0)
        val puck = UserPuck(position = GeoPoint(3.0, 4.0), bearing = 90f)
        assertEquals(GeoPoint(3.0, 4.0), puck.position)
        assertEquals(RouteStyle(), RouteStyle())
        assertNotEquals(RouteStyle(width = androidx.compose.ui.unit.Dp(4f)), RouteStyle(width = androidx.compose.ui.unit.Dp(8f)))
    }
}
