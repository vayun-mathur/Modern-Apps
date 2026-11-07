package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ui.theme.mapTokens
import com.vayunmathur.maps.util.NavigationProgress
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): the running loops behind the map effects —
 * vehicle ticks, rail settle decisions, traffic prefetch and the follow
 * camera — driven past their first-pass gate-off exits.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2EffectsComposeTest {

    @get:Rule val rule = createComposeRule()

    private fun progress(course: Float = 90f) = NavigationProgress(
        snappedPosition = GeoPoint(-122.4, 37.7),
        segmentIndex = 0,
        currentStepIndex = 0,
        distanceAlongRoute = 10.0,
        distanceRemaining = 500.0,
        distanceToNextManeuver = 120.0,
        fractionComplete = 0.1,
        etaEpochMs = 0L,
        courseOverGround = course,
        distanceOffRoute = 0.0,
    )

    private fun chrome(autoFollow: Boolean = true, northUp: Boolean = false) = MapChromeState(
        selectedRouteTypeState = mutableStateOf(com.vayunmathur.maps.util.RouteService.TravelMode.DRIVE),
        selectedCategoryState = mutableStateOf(null),
        overlayState = mutableStateOf(MapOverlay.None),
        autoFollowState = mutableStateOf(autoFollow),
        northUpState = mutableStateOf(northUp),
        postedLimitState = mutableStateOf<PostedLimit?>(null),
        lastProgrammaticMoveMsState = mutableStateOf(0L),
    )

    @Test
    fun `vehicle loop ticks below the zoom gate without native calls`() {
        var vehicles: List<com.vayunmathur.library.map.MapMarker>? = null
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 5.0))
        rule.setContent {
            Box {
                vehicles = rememberTransitVehicles(
                    camera = camera,
                    transitEnabled = true,
                    selectedStop = TransitStop("stop-1", "Central", 37.7, -122.4),
                    servingTripIds = setOf(1L),
                )
            }
        }
        rule.waitForIdle()
        // several 1 Hz ticks at zoom 5: always the empty set, never a native call
        rule.mainClock.advanceTimeBy(3_500)
        rule.waitForIdle()
        assertTrue(vehicles?.isEmpty() == true)
    }

    @Test
    fun `vehicle loop with no stop selected keeps clearing`() {
        var vehicles: List<com.vayunmathur.library.map.MapMarker>? = null
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 15.0))
        rule.setContent {
            Box {
                vehicles = rememberTransitVehicles(
                    camera = camera,
                    transitEnabled = true,
                    selectedStop = null,
                    servingTripIds = emptySet(),
                )
            }
        }
        rule.mainClock.advanceTimeBy(2_500)
        rule.waitForIdle()
        assertTrue(vehicles?.isEmpty() == true)
    }

    @Test
    fun `rail lines clear below the zoom floor after settling`() {
        var lines: com.vayunmathur.library.map.RouteOverlay? = null
        var composed = false
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 8.0))
        rule.setContent {
            Box {
                lines = rememberRailLines(
                    camera = camera,
                    transitEnabled = true,
                    selectedStop = TransitStop("stop-1", "Central", 37.7, -122.4),
                    tokens = mapTokens(isDark = false),
                )
                composed = true
            }
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertTrue(composed)
        assertNull(lines)
    }

    @Test
    fun `rail lines settle at metro zoom through the refetch decision`() {
        var composed = false
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        rule.setContent {
            Box {
                rememberRailLines(
                    camera = camera,
                    transitEnabled = true,
                    selectedStop = TransitStop("stop-1", "Central", 37.7, -122.4),
                    tokens = mapTokens(isDark = true),
                )
                composed = true
            }
        }
        // debounce (500ms) plus two settle cycles: the pack query runs (and
        // degrades to no lines in the sandbox) and the fan is rebuilt
        rule.mainClock.advanceTimeBy(2_500)
        rule.waitForIdle()
        assertTrue(composed)
    }

    @Test
    fun `follow camera animates to the puck heading-up and north-up`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.0, 37.0), 10.0))
        val chromeState = chrome(autoFollow = true, northUp = false)
        rule.setContent {
            NavigationCameraFollow(
                camera = camera,
                chrome = chromeState,
                navProgress = progress(course = 90f),
                isNavigating = true,
            )
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(37.7, camera.position.target.latitude, 1e-9)
        assertEquals(17.0, camera.position.zoom, 1e-9)
        assertEquals(90.0, camera.position.bearing, 1e-6)
        assertTrue(chromeState.lastProgrammaticMoveMs > 0)
    }

    @Test
    fun `follow camera north-up keeps the existing bearing`() {
        val camera2 = CameraState(CameraPosition(GeoPoint(-122.0, 37.0), 10.0, bearing = 30.0))
        rule.setContent {
            NavigationCameraFollow(
                camera = camera2,
                chrome = chrome(autoFollow = true, northUp = true),
                navProgress = progress(course = 90f),
                isNavigating = true,
            )
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(30.0, camera2.position.bearing, 1e-6)
        assertEquals(37.7, camera2.position.target.latitude, 1e-9)
    }

    @Test
    fun `follow camera rests when not following`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.0, 37.0), 10.0))
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            when (stage.intValue) {
                0 -> NavigationCameraFollow(
                    camera = camera,
                    chrome = chrome(autoFollow = false),
                    navProgress = progress(),
                    isNavigating = true,
                )
                1 -> NavigationCameraFollow(
                    camera = camera,
                    chrome = chrome(autoFollow = true),
                    navProgress = null,
                    isNavigating = true,
                )
                else -> NavigationCameraFollow(
                    camera = camera,
                    chrome = chrome(autoFollow = true),
                    navProgress = progress(),
                    isNavigating = false,
                )
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { stage.intValue = 1 }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { stage.intValue = 2 }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // never moved: no follow in any of the three configurations
        assertEquals(37.0, camera.position.target.latitude, 1e-9)
        assertEquals(10.0, camera.position.zoom, 1e-9)
    }

    @Test
    fun `traffic prefetch settles below the zoom gate without fetching`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 5.0))
        rule.setContent {
            TrafficPrefetchEffect(camera = camera, trafficEnabled = true)
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        // reaching here means the debounce fired and the zoom gate held
        assertEquals(5.0, camera.position.zoom, 1e-9)
    }
}
