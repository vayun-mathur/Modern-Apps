package com.vayunmathur.library.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The route/rail JNI packing, which now runs on the render thread instead of the main thread
 * (navigation re-pushes the whole route every 5 m of travel).
 */
class PackPolylinesTest {

    @Test
    fun drawable_segments_flatten_in_order_and_short_ones_drop() {
        val packed = assertNotNull(
            packPolylines(
                listOf(
                    RouteSegment(listOf(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)), Color.Red),
                    RouteSegment(listOf(GeoPoint(9.0, 9.0)), Color.Green),
                    RouteSegment(listOf(GeoPoint(5.0, 6.0), GeoPoint(7.0, 8.0), GeoPoint(9.0, 10.0)), Color.Blue),
                ),
            ),
        )
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), packed.flat)
        assertContentEquals(intArrayOf(2, 3), packed.lengths)
        assertContentEquals(intArrayOf(Color.Red.toArgb(), Color.Blue.toArgb()), packed.colors)
    }

    @Test
    fun nothing_drawable_packs_to_null() {
        assertNull(packPolylines(null))
        assertNull(packPolylines(emptyList()))
        assertNull(packPolylines(listOf(RouteSegment(listOf(GeoPoint(0.0, 0.0)), Color.Red))))
    }

    @Test
    fun a_cross_country_route_packs_and_reports_its_cost() {
        // ~2,000 km of driving at a vertex every ~20 m, split into 400 steps.
        val steps = (0 until 400).map { s ->
            RouteSegment(List(250) { i -> GeoPoint(-120.0 + s * 0.05 + i * 2e-4, 37.0) }, Color.Red)
        }
        packPolylines(steps)
        val millis = (1..5).minOf {
            val t = System.nanoTime()
            assertNotNull(packPolylines(steps))
            (System.nanoTime() - t) / 1e6
        }
        println("route packing: 100000 points in ${"%.2f".format(millis)} ms (now on the render thread)")
    }
}
