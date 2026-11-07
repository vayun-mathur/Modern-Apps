package com.vayunmathur.maps.ui.map

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Slice 1/3 second pass (maps): [MapSurface] composed in the sandbox. The
 * Vulkan renderer cannot present frames here, but the surface's own wiring
 * (options, markers, overlays, effects) is ordinary Compose state work and
 * must survive composition without a live renderer.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2MapSurfaceProbeTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun chrome() = MapChromeState(
        selectedRouteTypeState = mutableStateOf(RouteService.TravelMode.DRIVE),
        selectedCategoryState = mutableStateOf(null),
        overlayState = mutableStateOf(MapOverlay.None),
        autoFollowState = mutableStateOf(true),
        northUpState = mutableStateOf(false),
        postedLimitState = mutableStateOf<PostedLimit?>(null),
        lastProgrammaticMoveMsState = mutableStateOf(0L),
    )

    @Test
    fun `map surface composes its chrome wiring without a renderer`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        var composed = false
        rule.setContent {
            MapSurface(
                camera = camera,
                chrome = chrome(),
                viewModel = SelectedFeatureViewModel(app),
                searchViewModel = MapsSearchViewModel(app),
                transitViewModel = TransitStopsViewModel(app),
                sheetState = null,
                selectedFeature = null,
                route = null,
                userPosition = GeoPoint(0.0, 0.0),
                userBearing = null,
                navProgress = null,
                searchResults = emptyList(),
                savedPlaces = emptyList(),
                parkingSpot = null,
                familyMembers = emptyList(),
                trafficEnabled = false,
                satelliteEnabled = false,
                safetyEnabled = false,
                transitEnabled = false,
                globeEnabled = false,
                body = MapBody.Earth,
                moonTextures = null,
                selectedTransitStop = null,
                darkBasemap = false,
            )
            composed = true
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertTrue(composed)
    }

    @Test
    fun `map surface composes with route pins and every layer on`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 15.0))
        val place = com.vayunmathur.maps.data.SpecificFeature.GenericPlace(
            name = "Cafe", phone = null, website = null, openingHours = null,
            position = GeoPoint(-122.4, 37.7),
        )
        val route = RouteService.Route(
            10.minutes, 500.0,
            listOf(GeoPoint(-122.4, 37.7), GeoPoint(-122.3, 37.8)),
            listOf(
                RouteService.Step(
                    distanceMeters = 100.0,
                    staticDuration = 1.minutes,
                    polyline = listOf(GeoPoint(-122.4, 37.7), GeoPoint(-122.3, 37.8)),
                    navInstruction = RouteService.API.NavInstruction(),
                    travelMode = RouteService.TravelMode.DRIVE,
                ),
            ),
        )
        var composed = false
        rule.setContent {
            MapSurface(
                camera = camera,
                chrome = chrome(),
                viewModel = SelectedFeatureViewModel(app),
                searchViewModel = MapsSearchViewModel(app),
                transitViewModel = TransitStopsViewModel(app),
                sheetState = null,
                selectedFeature = com.vayunmathur.maps.data.SpecificFeature.Route(listOf(place, null)),
                route = route,
                userPosition = GeoPoint(-122.4, 37.7),
                userBearing = 45f,
                navProgress = null,
                searchResults = emptyList(),
                savedPlaces = emptyList(),
                parkingSpot = null,
                familyMembers = emptyList(),
                trafficEnabled = true,
                satelliteEnabled = true,
                safetyEnabled = true,
                transitEnabled = true,
                globeEnabled = false,
                body = MapBody.Earth,
                moonTextures = null,
                selectedTransitStop = com.vayunmathur.maps.data.transit.TransitStop(
                    "stop-1", "Central", 37.7, -122.4,
                ),
                darkBasemap = true,
            )
            composed = true
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertTrue(composed)
    }
}
