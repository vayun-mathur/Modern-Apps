package com.vayunmathur.library.map

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runTest

/** Animation, projection providers, and globe blend helpers. */
class CameraAnimateTest {

    @Test fun awaitProjection_waitsForViewport() = runTest {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        var got: Projection? = null
        val job = launch { got = camera.awaitProjection() }
        assertEquals(null, got)
        camera.setViewport(Size(400f, 800f))
        job.join()
        assertNotNull(got)
    }

    @Test fun cameraPosition_copyKeepsBearingPitch() {
        val pos = CameraPosition(GeoPoint(179.0, 10.0), 10.0, bearing = 350.0, pitch = 20.0)
        val moved = pos.copy(target = GeoPoint(-179.0, 20.0), zoom = 12.0)
        assertEquals(350.0, moved.bearing, absoluteTolerance = 1e-9)
        assertEquals(20.0, moved.pitch, absoluteTolerance = 1e-9)
        assertEquals(12.0, moved.zoom, absoluteTolerance = 1e-9)
    }

    @Test fun projection_providers_areUsed() {
        val camera = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 10.0))
        camera.setViewport(Size(400f, 800f))
        val label = PlacedLabel(layerId = "poi", name = "Cafe", kind = "cafe", position = GeoPoint(0.0, 0.0), featureId = 1L, regionId = 7L)
        camera.labelQueryProvider = { _, _ -> listOf(label) }
        camera.markerPickProvider = { x, y -> if (x > 0f && y > 0f) 99L else 0L }
        val proj = assertNotNull(camera.projection)
        assertEquals(listOf(label), proj.queryRenderedLabels(DpRect(0.dp, 0.dp, 10.dp, 10.dp), setOf("poi")))
        assertEquals(99L, proj.pickMarker(5f, 5f))
        assertEquals(0L, proj.pickMarker(-1f, 5f))
    }

    @Test fun globeRadius_blendsToFlat() {
        val low = globeRadius(2.0, 0.0)
        val high = globeRadius(12.0, 0.0)
        assertTrue(low > 0.0 && high > 0.0)
        // Past detail zoom the sphere matches the flat world radius.
        val flat = Mercator.worldSize(12.0) / (2.0 * Math.PI)
        assertEquals(flat, high, absoluteTolerance = flat * 0.01)
    }

    @Test fun renderState_reasons() {
        assertEquals(MapRenderState.Initialising, MapRenderState.Initialising)
        val un = MapRenderState.Unavailable(MapRenderState.Reason.RendererLibraryMissing)
        assertEquals(MapRenderState.Reason.RendererLibraryMissing, un.reason)
        assertTrue(MapRenderState.Reason.entries.isNotEmpty())
    }
}
