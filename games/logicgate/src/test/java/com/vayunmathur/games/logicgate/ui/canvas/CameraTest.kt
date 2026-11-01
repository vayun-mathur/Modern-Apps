package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraTest {

    private fun assertNear(expected: Offset, actual: Offset) =
        assertTrue(abs(expected.x - actual.x) < 1e-3f && abs(expected.y - actual.y) < 1e-3f, "$expected vs $actual")

    @Test
    fun worldAndScreenRoundTrip() {
        val camera = Camera(offset = Offset(37f, -12f), scale = 2.5f)
        val world = Offset(100f, 40f)
        assertNear(world, camera.toWorld(camera.toScreen(world)))
    }

    @Test
    fun zoomKeepsThePivotStill() {
        val camera = Camera(offset = Offset(10f, 20f), scale = 1f)
        val pivot = Offset(200f, 300f)
        val before = camera.toWorld(pivot)
        val zoomed = camera.zoomAbout(pivot, 2f, minScale = 0.3f, maxScale = 3f)
        assertEquals(2f, zoomed.scale)
        assertNear(before, zoomed.toWorld(pivot))
    }

    @Test
    fun zoomIsClamped() {
        val camera = Camera(scale = 1f)
        assertEquals(3f, camera.zoomAbout(Offset.Zero, 10f, 0.3f, 3f).scale)
        assertEquals(0.3f, camera.zoomAbout(Offset.Zero, 0.01f, 0.3f, 3f).scale)
    }

    @Test
    fun fitCentresTheBoundsInTheViewport() {
        val bounds = Rect(0f, 0f, 400f, 200f)
        val viewport = Size(1000f, 1000f)
        val camera = Camera.fit(bounds, viewport, padding = 100f, minScale = 0.1f, maxScale = 10f)
        assertEquals(2f, camera.scale)
        assertNear(Offset(500f, 500f), camera.toScreen(bounds.center))
    }
}
