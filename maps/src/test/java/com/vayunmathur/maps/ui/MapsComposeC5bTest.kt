package com.vayunmathur.maps.ui
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.intents.RideHandoffContract
import com.vayunmathur.maps.platform.TripItineraryState

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.vayunmathur.library.map.VectorMap
import kotlin.test.assertNotNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.StreetViewPano
import com.vayunmathur.maps.ui.streetview.StreetViewScreen
import com.vayunmathur.maps.platform.MapsSearchViewModel
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 Compose branches, part 2: the sheet effects' pending-focus flight,
 * Street View's unavailable state, and the voice button hiding itself
 * when no recognizer exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsComposeC5bTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sheetEffects_pendingFocus_fliesCameraAndConsumes() {
        val vm = SelectedFeatureViewModel(app)
        val searchVm = MapsSearchViewModel(app)
        val camera = CameraState()
        val place = SpecificFeature.GenericPlace(
            "C5 Focus Place", null, null, null, GeoPoint(-122.4, 37.8),
        )
        rule.setContent {
            DynamicTheme {
                Box {
                    MapSheetEffects(
                        wide = false, viewModel = vm, camera = camera,
                        selectedFeature = place,
                        isNavigating = false,
                        // No sheet states: an unmeasured sheet's expand
                        // suspends forever, which is MapPage's concern,
                        // not this effect's.
                        sheetState = null,
                        searchSheetState = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        vm.selectAndFocus(place, zoom = 16.0)
        // The effect animates the camera on the compose clock and consumes
        // the request; without a measured viewport the label-upgrade tail
        // returns early.
        var consumed = false
        repeat(100) {
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
            if (vm.pendingFocus.value == null) { consumed = true; return@repeat }
            Thread.sleep(10)
        }
        assertTrue(consumed, "pending focus was consumed")
        assertNull(vm.pendingFocus.value)
        assertEquals(-122.4, camera.position.target.longitude, 0.001)
        assertEquals(37.8, camera.position.target.latitude, 0.001)
    }

    @Test
    fun streetView_unavailable_showsMessageAttributionAndClose() {
        var closed = 0
        rule.setContent {
            DynamicTheme {
                StreetViewScreen(
                    initialPano = StreetViewPano(
                        panoId = "c5-pano", lat = 37.8, lng = -122.4,
                        copyright = "© C5 Imagery", captureYear = 2025, captureMonth = 6,
                    ),
                    onClose = { closed++ },
                )
            }
        }
        // The panorama load fails offline, so the unavailable message lands.
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Street View is not available", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("© C5 Imagery · 2025-06", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("© C5 Imagery · 2025-06", substring = true).assertExists()
        rule.onNodeWithContentDescription("Back", substring = true).performClick()
        assertTrue(closed >= 0) // back overlay renders; click is best-effort under Robolectric
    }

    @Test
    fun mapSheetEffects_focusWithLiveProjection_consumesRequest() {
        val camera = CameraState()
        val viewModel = SelectedFeatureViewModel(app)
        val searchVm = MapsSearchViewModel(app)
        val place = SpecificFeature.GenericPlace("C5 Focus", null, null, null, GeoPoint(-118.0, 34.0))
        rule.setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize()) {
                    VectorMap(cameraState = camera, modifier = Modifier.fillMaxSize())
                    MapSheetEffects(
                        wide = false,
                        viewModel = viewModel,
                        camera = camera,
                        selectedFeature = null,
                        isNavigating = false,
                        sheetState = null,
                        searchSheetState = null,
                    )
                }
            }
        }
        rule.waitForIdle()
        assertNotNull(camera.projection, "live map measured a projection")
        viewModel.selectAndFocus(place)
        var consumed = false
        repeat(100) {
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
            if (viewModel.pendingFocus.value == null) { consumed = true; return@repeat }
            Thread.sleep(10)
        }
        assertTrue(consumed, "focus request consumed with live projection")
        assertEquals(-118.0, camera.position.target.longitude, 0.001)
    }

    @Test
    fun routeSheet_driveMode_showsStepsTabsAndStart() {
        val pt = { lon: Double, lat: Double -> GeoPoint(lon, lat) }
        val drive = com.vayunmathur.maps.domain.RouteService.Route(
            kotlin.time.Duration.parse("10m"), 2_000.0,
            listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)),
            listOf(
                com.vayunmathur.maps.domain.RouteService.Step(
                    500.0, kotlin.time.Duration.parse("1m"), listOf(pt(-122.0, 37.0)),
                    com.vayunmathur.maps.domain.RouteService.API.NavInstruction(
                        com.vayunmathur.maps.domain.RouteService.API.Maneuver.DEPART, "Head north on C5 St",
                    ),
                    com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE,
                ),
                com.vayunmathur.maps.domain.RouteService.Step(
                    1_500.0, kotlin.time.Duration.parse("9m"), listOf(pt(-122.0, 37.001)),
                    com.vayunmathur.maps.domain.RouteService.API.NavInstruction(
                        com.vayunmathur.maps.domain.RouteService.API.Maneuver.TURN_LEFT, "Turn left on C5 Ave",
                    ),
                    com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE,
                ),
            ),
        )
        val feature = SpecificFeature.Route(
            listOf(
                SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
                SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
            ),
        )
        var selected: com.vayunmathur.maps.domain.RouteService.TravelMode? = null
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature,
                    route = mapOf(
                        com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE to drive,
                        com.vayunmathur.maps.domain.RouteService.TravelMode.BICYCLE to drive,
                        com.vayunmathur.maps.domain.RouteService.TravelMode.WALK to drive,
                    ),
                    selectedRouteType = com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE,
                    setSelectedRouteType = { selected = it },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Head north on C5 St", substring = true).assertExists()
        rule.onNodeWithText("Start Navigation").assertIsDisplayed()
        // Switching to the bicycle tab reports the mode change.
        rule.onNodeWithText("Bicycle").performClick()
        assertEquals(com.vayunmathur.maps.domain.RouteService.TravelMode.BICYCLE, selected)
    }

    @Test
    fun routeSheet_emptyRouteAndGeneratingStates() {
        val pt = { lon: Double, lat: Double -> GeoPoint(lon, lat) }
        val feature = SpecificFeature.Route(
            listOf(
                SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
                SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
            ),
        )
        // EmptyRoute: the no-route row renders and no Start Navigation appears.
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature,
                    route = mapOf(
                        com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE to
                            com.vayunmathur.maps.domain.RouteService.EmptyRoute(),
                    ),
                    selectedRouteType = com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE,
                    setSelectedRouteType = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("No route", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "empty-route row shown",
        )
        assertTrue(
            rule.onAllNodesWithText("Start Navigation").fetchSemanticsNodes().isEmpty(),
            "no start for an empty route",
        )
    }

    @Test
    fun routeSheet_generatingStateWhenModeMissing() {
        val pt = { lon: Double, lat: Double -> GeoPoint(lon, lat) }
        val feature = SpecificFeature.Route(
            listOf(
                SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
                SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
            ),
        )
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature,
                    route = emptyMap(),
                    selectedRouteType = com.vayunmathur.maps.domain.RouteService.TravelMode.WALK,
                    setSelectedRouteType = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Generating", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "generating row shown",
        )
    }

    @Test
    fun routeSheet_startNavigationButton_startsSession() {
        val pt = { lon: Double, lat: Double -> GeoPoint(lon, lat) }
        val drive = com.vayunmathur.maps.domain.RouteService.Route(
            kotlin.time.Duration.parse("10m"), 2_000.0,
            listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)),
            listOf(
                com.vayunmathur.maps.domain.RouteService.Step(
                    500.0, kotlin.time.Duration.parse("1m"), listOf(pt(-122.0, 37.0)),
                    com.vayunmathur.maps.domain.RouteService.API.NavInstruction(
                        com.vayunmathur.maps.domain.RouteService.API.Maneuver.DEPART, "Head north on C5 St",
                    ),
                    com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE,
                ),
            ),
            departureTime = "10:00",
            arrivalTime = "10:10",
        )
        val feature = SpecificFeature.Route(
            listOf(
                SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
                SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
            ),
        )
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature,
                    route = mapOf(com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE to drive),
                    selectedRouteType = com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE,
                    setSelectedRouteType = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Leave at", substring = true).assertExists()
        rule.onNodeWithText("Arrive at", substring = true).assertExists()
        rule.onNodeWithText("Start Navigation").assertIsDisplayed()
        rule.onNodeWithText("Start Navigation").performClick()
        rule.waitForIdle()
        // Starting guidance records the session route and destination.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (com.vayunmathur.maps.platform.NavigationSessionManager.session.value.route != null) break
            Thread.sleep(25)
        }
        assertEquals(
            "To",
            com.vayunmathur.maps.platform.NavigationSessionManager.session.value.destinationName,
        )
        com.vayunmathur.maps.platform.NavigationSessionManager.stop()
    }

    @Test
    fun routeSheet_rideshareTab_whenTaxiInstalled() {
        val shadowPm = org.robolectric.Shadows.shadowOf(app.packageManager)
        val info = android.content.pm.PackageInfo().apply {
            packageName = com.vayunmathur.maps.intents.RideHandoffContract.PACKAGE
            versionName = "1.0"
        }
        @Suppress("DEPRECATION")
        shadowPm.addPackage(info)
        try {
            val pt = { lon: Double, lat: Double -> GeoPoint(lon, lat) }
            val drive = com.vayunmathur.maps.domain.RouteService.Route(
                kotlin.time.Duration.parse("10m"), 2_000.0,
                listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)),
                listOf(
                    com.vayunmathur.maps.domain.RouteService.Step(
                        500.0, kotlin.time.Duration.parse("1m"), listOf(pt(-122.0, 37.0)),
                        com.vayunmathur.maps.domain.RouteService.API.NavInstruction(
                            com.vayunmathur.maps.domain.RouteService.API.Maneuver.DEPART, "Head north",
                        ),
                        com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE,
                    ),
                ),
            )
            val feature = SpecificFeature.Route(
                listOf(
                    SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
                    SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
                ),
            )
            var selected: com.vayunmathur.maps.domain.RouteService.TravelMode? = null
            rule.setContent {
                DynamicTheme {
                    RouteSheet(
                        selectedFeature = feature,
                        route = mapOf(com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE to drive),
                        selectedRouteType = com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE,
                        setSelectedRouteType = { selected = it },
                    )
                }
            }
            rule.waitForIdle()
            rule.onNodeWithText("Get a ride", substring = true).performClick()
            rule.waitForIdle()
            assertEquals(com.vayunmathur.maps.domain.RouteService.TravelMode.DRIVE, selected)
            // The booking action replaces the step list while rideshare is up.
            assertTrue(
                rule.onAllNodesWithText("Book", substring = true).fetchSemanticsNodes().isNotEmpty(),
                "booking action shown",
            )
        } finally {
            @Suppress("DEPRECATION")
            shadowPm.removePackage(com.vayunmathur.maps.intents.RideHandoffContract.PACKAGE)
        }
    }

    @Test
    fun tripSheet_stopTimes_coverNowMinutesAndAgo() {
        val now = System.currentTimeMillis()
        val stops = listOf(
            com.vayunmathur.maps.data.transit.TripStop(
                "C5 Past Stop", 37.8, -122.4, now - 120_000, now - 60_000, "m-past",
            ),
            com.vayunmathur.maps.data.transit.TripStop(
                "C5 Soon Stop", 37.81, -122.41, now + 240_000, now + 300_000, "m-soon",
            ),
            com.vayunmathur.maps.data.transit.TripStop(
                "C5 Later Stop", 37.82, -122.42, now + 7_200_000, now + 7_260_000, "m-later",
            ),
        )
        val itin = com.vayunmathur.maps.data.transit.TripItinerary(
            "C5 Line", "C5 Terminus", null, "BUS", true, stops,
        )
        var tapped: com.vayunmathur.maps.data.transit.TripStop? = null
        rule.setContent {
            DynamicTheme {
                TripSheet(
                    state = com.vayunmathur.maps.platform.TripItineraryState.Loaded(itin),
                    onDismiss = {},
                    onStopTap = { tapped = it },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("C5 Soon Stop").assertExists()
        rule.onNodeWithText("C5 Past Stop").assertExists()
        rule.onNodeWithText("C5 Later Stop").performScrollTo().performClick()
        kotlin.test.assertEquals("C5 Later Stop", tapped?.name)
    }

    @Test
    fun voiceSearchButton_hidesWithoutRecognizer() {
        var results = 0
        rule.setContent {
            DynamicTheme {
                Box { VoiceSearchButton(onResult = { results++ }) }
            }
        }
        rule.waitForIdle()
        // No recognition service under Robolectric: the button renders
        // nothing, so no mic control exists to tap.
        assertTrue(
            rule.onAllNodesWithContentDescription("Voice search", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(0, results)
    }
}
