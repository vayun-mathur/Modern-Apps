package com.vayunmathur.library.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Geometry behind [zoomGestures]: anchoring, clamping, the double-tap toggle. */
class ZoomStateTest {

    private val box = IntSize(400, 800)
    private val center = Offset(200f, 400f)

    @Test
    fun `default is identity and not zoomed`() {
        val z = ZoomState()
        assertEquals(1f, z.scale)
        assertEquals(Offset.Zero, z.offset)
        assertFalse(z.isZoomed)
    }

    @Test
    fun `pinch around the centre scales without moving`() {
        val z = ZoomState().transformed(2f, Offset.Zero, center, box)
        assertEquals(2f, z.scale)
        assertEquals(Offset.Zero, z.offset)
        assertTrue(z.isZoomed)
    }

    @Test
    fun `pinch keeps the content point under the centroid`() {
        val centroid = Offset(300f, 500f)
        val start = ZoomState(scale = 1.5f, offset = Offset(-20f, 10f))
        val z = start.transformed(2f, Offset.Zero, centroid, box)
        // Content point under the centroid, measured from the centre, before and after.
        val anchor = centroid - center
        val before = (anchor - start.offset) / start.scale
        val after = (anchor - z.offset) / z.scale
        assertEquals(3f, z.scale)
        assertEquals(before.x, after.x, 0.001f)
        assertEquals(before.y, after.y, 0.001f)
    }

    @Test
    fun `scale is clamped to fit and to the ceiling`() {
        assertEquals(ZoomState(), ZoomState(scale = 2f, offset = Offset(50f, 50f)).transformed(0.1f, Offset.Zero, center, box))
        assertEquals(
            ZoomState.DEFAULT_MAX_SCALE,
            ZoomState(scale = 4f).transformed(10f, Offset.Zero, center, box).scale,
        )
    }

    @Test
    fun `pan is clamped so content covers the container`() {
        val z = ZoomState(scale = 2f).transformed(1f, Offset(10_000f, -10_000f), center, box)
        assertEquals(Offset(200f, 400f), maxZoomOffset(2f, box))
        assertEquals(Offset(200f, -400f), z.offset)
    }

    @Test
    fun `pan at fit scale has no room`() {
        assertEquals(Offset.Zero, maxZoomOffset(1f, box))
        assertEquals(Offset.Zero, clampZoomOffset(Offset(30f, -30f), 1f, box))
    }

    @Test
    fun `double tap zooms into the tapped point and back`() {
        val tap = Offset(300f, 600f)
        val zoomed = ZoomState().toggledAt(tap, box)
        assertEquals(ZoomState.DEFAULT_DOUBLE_TAP_SCALE, zoomed.scale)
        // The tapped point stays where it was: offset = -(tap - centre) * (scale - 1).
        assertEquals(Offset(-150f, -300f), zoomed.offset)
        assertEquals(ZoomState(), zoomed.toggledAt(tap, box))
    }

    @Test
    fun `double tap near an edge stays clamped`() {
        val zoomed = ZoomState().toggledAt(Offset(0f, 0f), box, zoomedScale = 2f)
        assertEquals(Offset(200f, 400f), zoomed.offset)
    }
}
