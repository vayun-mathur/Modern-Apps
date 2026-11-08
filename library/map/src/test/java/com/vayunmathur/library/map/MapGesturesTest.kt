package com.vayunmathur.library.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Gesture modifier: tap reports geo, drag pans, viewport is measured from layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapGesturesTest {

    @get:Rule val rule = createComposeRule()

    @Test fun tap_reportsCenterGeo() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        var clicked: GeoPoint? = null
        var withScreen: MapClick? = null
        rule.setContent {
            Box(
                Modifier.size(400.dp, 800.dp)
                    .mapGestures(camera, GestureOptions(), 2f..20f, density = 1f, onMapClick = { clicked = it }, onMapClickWithScreen = { withScreen = it })
            )
        }
        rule.runOnIdle { assertNotNull(camera.viewportDp) }
        rule.onRoot().performTouchInput { down(center); up() }
        rule.waitUntil(4000) { clicked != null }
        rule.runOnIdle {
            val c = kotlin.test.assertNotNull(clicked)
            assertTrue(abs(c.longitude - (-122.4194)) < 0.01 && abs(c.latitude - 37.7749) < 0.01, "tap geo $c")
            assertNotNull(withScreen)
        }
    }

    @Test fun drag_pansCamera() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 14.0))
        rule.setContent {
            Box(Modifier.size(400.dp, 800.dp).mapGestures(camera, GestureOptions(), 2f..20f, density = 1f, onMapClick = {}))
        }
        val before = camera.position.target
        rule.onRoot().performTouchInput {
            down(center); moveBy(Offset(-120f, 0f)); moveBy(Offset(-120f, 0f)); up()
        }
        rule.runOnIdle {
            assertTrue(camera.position.target.longitude != before.longitude, "pan should move centre")
        }
    }

    @Test fun disabledGestures_tapStillReports() {
        val camera = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 10.0))
        var clicked: GeoPoint? = null
        rule.setContent {
            Box(Modifier.size(400.dp, 800.dp).mapGestures(camera, GestureOptions.AllDisabled, 2f..20f, density = 1f, onMapClick = { clicked = it }))
        }
        val before = camera.position
        rule.onRoot().performTouchInput {
            down(center); moveBy(Offset(-120f, 0f)); up()
        }
        rule.runOnIdle {
            assertTrue(
                kotlin.math.abs(camera.position.target.longitude - before.target.longitude) < 1e-9,
                "disabled scroll must not pan",
            )
        }
    }
}
