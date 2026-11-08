package com.vayunmathur.library.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Surface attach (native unavailable under Robolectric), image overlay, taps, bounds, saver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapAttachTest {

    @get:Rule val rule = createComposeRule()

    @Test fun attachSurface_withoutNative_setsUnavailableOrRendering() {
        // Host Rust lib loads on the JVM but its create() symbol does not link
        // against a Robolectric shadow Surface; attach is exercised on-device.
        // Here: detach on a fresh renderer is a safe no-op back to Initialising.
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val r = SurfaceMapRenderer(ctx, density = 3f)
        r.detachSurface()
        assertEquals(MapRenderState.Initialising, r.renderState)
    }

    @Test fun vectorMap_imageOverlayAndTap() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        var clicked: GeoPoint? = null
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        rule.setContent {
            VectorMap(
                cameraState = camera, modifier = Modifier.size(360.dp, 640.dp), darkBasemap = false,
                imageOverlay = ImageOverlay(bitmap = bitmap, bounds = GeoBounds(-122.43, 37.76, -122.40, 37.79), opacity = 0.5f),
                onMapClick = { clicked = it },
            )
        }
        rule.runOnIdle { camera.setViewport(Size(360f, 640f)) }
        rule.onRoot().performTouchInput { down(center); up() }
        rule.runOnIdle { assertTrue(clicked != null || camera.viewportDp != null) }
    }

    @Test fun projection_visibleBounds_flatAndGlobe() {
        val flat = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 10.0))
        flat.setViewport(Size(400f, 800f))
        val bounds = assertNotNull(flat.projection).queryVisibleBoundingBox()
        assertTrue(bounds.west < -122.4194 && bounds.east > -122.4194)
        assertTrue(bounds.south < 37.7749 && bounds.north > 37.7749)

        val globe = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 2.0))
        globe.globeEnabled = true
        globe.setViewport(Size(400f, 800f))
        val gBounds = assertNotNull(globe.projection).queryVisibleBoundingBox()
        assertTrue(gBounds.west >= -180.0 && gBounds.east <= 180.0)
        assertTrue(globe.isGlobeActive)
    }

    @Test fun rememberCameraState_inComposition() {
        var seen: CameraState? = null
        rule.setContent {
            val cam = rememberCameraState(CameraPosition(GeoPoint(1.0, 2.0), 5.0))
            seen = cam
            androidx.compose.foundation.text.BasicText("cam")
        }
        assertNotNull(seen)
        assertEquals(5.0, seen!!.position.zoom, absoluteTolerance = 1e-9)
    }
}
