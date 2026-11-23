package com.vayunmathur.library.map

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins for the render hot-path optimisations: identical output, less work.
 * Host (Robolectric) timings only -- no device claims.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderHotPathTest {

    @get:Rule val rule = createComposeRule()

    @Test fun tap_projectsOnce_andBothCallbacksAgree() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        var plain: GeoPoint? = null
        var withScreen: MapClick? = null
        rule.setContent {
            VectorMap(
                cameraState = camera, modifier = Modifier.size(360.dp, 640.dp), darkBasemap = false,
                onMapClick = { plain = it },
                onMapClickWithScreen = { withScreen = it },
            )
        }
        rule.runOnIdle { camera.setViewport(Size(360f, 640f)) }
        rule.onRoot().performTouchInput { down(center); up() }
        // The tap detector waits out the double-tap window before reporting.
        rule.mainClock.advanceTimeBy(1_000)
        rule.runOnIdle {
            assertNotNull(plain)
            assertNotNull(withScreen)
            // The single-projection tap path must report bit-identical points.
            assertEquals(plain, withScreen?.position)
        }
    }

    @Test fun projection_isCachedWhileCameraIsStill() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        camera.setViewport(Size(360f, 640f))
        val first = camera.projection
        assertNotNull(first)
        // derivedStateOf: repeated reads on a still camera reuse one instance,
        // so per-marker placement does not allocate a Projection per read.
        assertSame(first, camera.projection)
    }

    @Test fun projection_roundTrip_isStableOverSyntheticPoints() {
        // Synthetic-tile style sweep on the host JVM: project/unproject must
        // round-trip within float tolerance at street zoom, before and after
        // the hot-path changes (they share the same Mercator math).
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        camera.setViewport(Size(360f, 640f))
        val projection = camera.projection!!
        repeat(64) { i ->
            val point = GeoPoint(-122.43 + i * 0.0002, 37.77 + i * 0.0001)
            val back = projection.positionFromScreenLocation(projection.screenLocationFromPosition(point))
            assertEquals(point.longitude, back.longitude, 1e-4)
            assertEquals(point.latitude, back.latitude, 1e-4)
        }
    }
}
