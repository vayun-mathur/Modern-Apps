package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the disabled/empty paths of the effectful map helpers —
 * [rememberTransitVehicles], [rememberRailLines], [TrafficPrefetchEffect],
 * [MapLayers] and [rememberMapChromeState] — composed with their gates off so
 * the early exits run without renderers, packs or clocks.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1MapEffectsComposeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `vehicles stay empty while the transit layer is off`() {
        var vehicles: List<com.vayunmathur.library.map.MapMarker>? = null
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        rule.setContent {
            Box {
                vehicles = rememberTransitVehicles(
                    camera = camera,
                    transitEnabled = false,
                    selectedStop = null,
                    servingTripIds = emptySet(),
                )
            }
        }
        rule.waitForIdle()
        assertTrue(vehicles?.isEmpty() == true)
    }

    @Test
    fun `rail lines stay null while the transit layer is off`() {
        var lines: com.vayunmathur.library.map.RouteOverlay? = null
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        val tokens = com.vayunmathur.maps.ui.theme.mapTokens(isDark = false)
        rule.setContent {
            Box {
                lines = rememberRailLines(
                    camera = camera,
                    transitEnabled = false,
                    selectedStop = null,
                    tokens = tokens,
                )
            }
        }
        rule.waitForIdle()
        assertEquals(null, lines)
    }

    @Test
    fun `the traffic prefetch rests while traffic is off`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        rule.setContent {
            Box {
                TrafficPrefetchEffect(camera = camera, trafficEnabled = false)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `map layers compose with everything off`() {
        rule.setContent {
            Box {
                MapLayers(satelliteEnabled = false, transitEnabled = false)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `remembered chrome state starts at the documented defaults`() {
        var chrome: MapChromeState? = null
        rule.setContent {
            Box {
                chrome = rememberMapChromeState()
            }
        }
        rule.waitForIdle()
        val c = chrome ?: error("expected chrome state")
        assertEquals(MapOverlay.None, c.overlay)
        assertEquals(true, c.autoFollow)
        assertEquals(false, c.northUp)
        assertEquals(null, c.postedLimit)
    }

    @Test
    fun `the follow camera rests while not navigating`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        var chrome: MapChromeState? = null
        rule.setContent {
            Box {
                chrome = rememberMapChromeState()
                val c = chrome ?: return@Box
                NavigationCameraFollow(
                    camera = camera,
                    chrome = c,
                    navProgress = null,
                    isNavigating = false,
                )
            }
        }
        rule.waitForIdle()
        // No animation fired: the camera never moved.
        assertEquals(12.0, camera.position.zoom)
        assertEquals(true, (chrome?.autoFollow == true))
    }
}
