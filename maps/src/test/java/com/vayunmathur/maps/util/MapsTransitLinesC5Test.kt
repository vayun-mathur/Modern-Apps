package com.vayunmathur.maps.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C5 raw rail-line conversion: undersized geometries are dropped,
 * colours and modes decode, and point pairs map to GeoPoints.
 */
class MapsTransitLinesC5Test {

    private fun rawLine(
        name: String = "N Judah",
        color: Int = 0xC60C30,
        routeType: Int = 0,
        coords: DoubleArray = doubleArrayOf(-122.0, 37.0, -122.1, 37.1, -122.2, 37.2),
        ordinal: Int = 1,
        lanes: Int = 2,
        taper: Int = 255,
    ) = OfflineRouter.RawRailLine(
        name = name,
        color = color,
        routeType = routeType,
        feed = "sfmta",
        coords = coords,
        ordinal = ordinal,
        lanes = lanes,
        taper = taper,
    )

    private fun invoke(name: String, vararg args: Any?): Any? {
        val method = OfflineRouterTransit::class.java.declaredMethods
            .first { it.name == name }
        method.isAccessible = true
        return method.invoke(OfflineRouterTransit, *args)
    }

    @Test
    fun `raw lines convert with colour mode and points`() {
        @Suppress("UNCHECKED_CAST")
        val lines = invoke(
            "rawLinesToRailLines",
            arrayOf(rawLine(), rawLine(name = "Too Short", coords = doubleArrayOf(-122.0, 37.0))),
        ) as List<com.vayunmathur.maps.data.transit.RailLine>
        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals("N Judah", line.name)
        assertEquals("C60C30", line.color)
        assertEquals(3, line.points.size)
        assertEquals(-122.0, line.points[0].longitude)
        assertEquals(37.0, line.points[0].latitude)
        assertEquals(1, line.ordinal)
        assertEquals(2, line.lanes)
    }

    @Test
    fun `zero colour becomes null and odd coords drop the tail`() {
        val line = invoke("lineToRailLine", rawLine(color = 0, coords = doubleArrayOf(-122.0, 37.0, -122.1, 37.1, 5.0)))
        assertTrue(line != null)
        line as com.vayunmathur.maps.data.transit.RailLine
        assertNull(line.color)
        assertEquals(2, line.points.size)
    }

    @Test
    fun `single point line is rejected`() {
        assertNull(invoke("lineToRailLine", rawLine(coords = doubleArrayOf(-122.0, 37.0))))
        assertNull(invoke("lineToRailLine", rawLine(coords = doubleArrayOf())))
    }
}
