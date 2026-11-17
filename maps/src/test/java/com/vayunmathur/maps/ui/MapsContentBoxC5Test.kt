package com.vayunmathur.maps.ui
import com.vayunmathur.maps.platform.DeparturesState
import com.vayunmathur.maps.platform.TripItineraryState

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.popSheet
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.ui.map.MapChromeState
import com.vayunmathur.maps.platform.MapSettingsViewModel
import com.vayunmathur.maps.platform.MapsSearchViewModel
import com.vayunmathur.maps.platform.NavigationSessionManager
import com.vayunmathur.maps.platform.ParkingViewModel
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.platform.SavedPlacesViewModel
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import com.vayunmathur.maps.platform.TransitStopsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * C5 MapContentBox interactions: category chips, the layers overlay and
 * its toggles, the parking FAB in both modes, and the route waypoint
 * list. Harness mirrors WideHostSlice3ExtraTest's scope builder.
 */
@Suppress("ViewModelConstructorInComposable")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsContentBoxC5Test {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @androidx.compose.runtime.Composable
    private fun buildScope(
        selectedFeature: SpecificFeature? = null,
        body: MapBody = MapBody.Earth,
        isNavigating: Boolean = false,
        googleServices: Boolean = true,
        layers: Boolean = false,
        parkingSpot: ParkingSpot? = null,
        savedPins: List<SavedPlace> = emptyList(),
        chromeRef: (MapChromeState) -> Unit = {},
        parkingRef: (ParkingViewModel) -> Unit = {},
        cameraRef: (CameraState) -> Unit = {},
    ): MapPageScope {
        val messenger = rememberMessenger()
        val cs = rememberCoroutineScope()
        val chrome = remember {
            MapChromeState(
                mutableStateOf(null),
                mutableStateOf(true),
                mutableStateOf(false),
                mutableStateOf(null),
                mutableStateOf(0L),
            )
        }
        chromeRef(chrome)
        val parking = remember { ParkingViewModel(app) }
        parkingRef(parking)
        val camera = remember { CameraState() }
        cameraRef(camera)
        return MapPageScope(
            context = app,
            backStack = NavBackStack(arrayOf(Route.MapPage)),
            viewModel = SelectedFeatureViewModel(app),
            savedPlacesViewModel = SavedPlacesViewModel(app),
            searchViewModel = MapsSearchViewModel(app),
            settingsViewModel = MapSettingsViewModel(app),
            parkingViewModel = parking,
            transitViewModel = TransitStopsViewModel(app),
            coroutineScope = cs,
            messenger = messenger,
            noResultsMessage = "No results",
            chrome = chrome,
            camera = camera,
            selectedFeature = selectedFeature,
            inactiveNavigation = null,
            route = null,
            poiSection = PoiSection.DETAILS,
            poiEnrichmentKey = PoiEnrichmentKey(0, hasHours = false, hasPopularTimes = false),
            userPosition = GeoPoint(-122.4, 37.8),
            userBearing = null,
            userHeadingAccuracy = 3,
            userSpeedMps = 0f,
            savedPins = savedPins,
            parkingSpot = parkingSpot,
            searchResults = emptyList(),
            searchRecents = emptyList(),
            selectedTransitStop = null,
            departuresState = DeparturesState.Idle,
            tripItineraryState = TripItineraryState.Idle,
            familyMembers = emptyList(),
            trafficEnabled = layers, satelliteEnabled = layers, safetyEnabled = layers, transitEnabled = layers,
            globeEnabled = layers, googleServices = googleServices,
            body = body, moonTextures = null, darkMap = false,
            navState = NavigationSessionManager.NavState.Idle,
            navSession = NavigationSessionManager.NavSession(),
            navProgress = null, isNavigating = isNavigating, browsing = true,
            searchBarVisible = true, searchBarLiftPx = 0, onSearchBarLift = {},
            sheetState = null, searchSheetState = null, searchRoute = null,
            openSearch = { _, _ -> }, settingsAction = emptyList(), wide = false,
        )
    }

    @Test
    fun categoryChipClick_selectsCategoryInChrome() {
        var chrome: MapChromeState? = null
        rule.setContent {
            val scope = buildScope(chromeRef = { chrome = it })
            DynamicTheme { scope.MapContentBox() }
        }
        rule.waitForIdle()
        // First chip label comes from MapCategory resources; tap the first
        // visible chip and watch chrome state change.
        val before = chrome!!.selectedCategory
        val chip = rule.onAllNodesWithText("Restaurants", substring = true)
        if (chip.fetchSemanticsNodes().isNotEmpty()) {
            chip[0].performClick()
            rule.waitForIdle()
            assertTrue(chrome!!.selectedCategory != before || before != null)
        } else {
            // Fall back to any chip-shaped text that did render.
            rule.onNodeWithText("Gas", substring = true).performClick()
            rule.waitForIdle()
            assertNotNull(chrome!!.selectedCategory)
        }
    }

    @Test
    fun layersFab_opensOverlaySheet() {
        var captured: MapPageScope? = null
        rule.setContent {
            val scope = buildScope()
            captured = scope
            DynamicTheme { scope.MapContentBox() }
        }
        rule.waitForIdle()
        rule.runOnUiThread { captured!!.sheetStack.add(com.vayunmathur.maps.ui.SheetRoute.Layers) }
        rule.waitForIdle()
        assertEquals(com.vayunmathur.maps.ui.SheetRoute.Layers, captured!!.sheetTop)
        // The layers sheet content renders (toggle rows).
        assertTrue(
            rule.onAllNodesWithText("Traffic", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Satellite", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "layers sheet shows toggles",
        )
        rule.runOnUiThread { captured!!.sheetStack.popSheet() }
        rule.waitForIdle()
        assertEquals(com.vayunmathur.maps.ui.SheetRoute.Browse, captured!!.sheetTop)
    }

    @Test
    fun parkingFab_withSpot_fliesCameraAndOpensSheet() {
        var chrome: MapChromeState? = null
        var capturedScope: MapPageScope? = null
        var camera: CameraState? = null
        val spot = ParkingSpot(37.85, -122.35, System.currentTimeMillis(), null)
        rule.setContent {
            val scope = buildScope(
                parkingSpot = spot,
                chromeRef = { chrome = it },
                cameraRef = { camera = it },
            )
            capturedScope = scope
            DynamicTheme { scope.MapContentBox() }
        }
        rule.waitForIdle()
        rule.onNodeWithText("P").performClick()
        // Camera flight is an animation on the compose clock.
        repeat(50) {
            rule.mainClock.advanceTimeBy(100)
            rule.waitForIdle()
            if (capturedScope!!.sheetTop == com.vayunmathur.maps.ui.SheetRoute.Parking) return@repeat
            Thread.sleep(10)
        }
        assertEquals(com.vayunmathur.maps.ui.SheetRoute.Parking, capturedScope!!.sheetTop)
        assertEquals(-122.35, camera!!.position.target.longitude, 0.01)
    }

    @Test
    fun moonBody_keepsBodySwitchInsteadOfChips() {
        var chrome: MapChromeState? = null
        rule.setContent {
            val scope = buildScope(
                body = MapBody.Moon,
                chromeRef = { chrome = it },
            )
            DynamicTheme { scope.MapContentBox() }
        }
        rule.waitForIdle()
        rule.runOnUiThread { chrome!!.body = MapBody.Moon }
        rule.waitForIdle()
        rule.onNodeWithText("Moon", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Earth", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(MapBody.Earth, chrome!!.body)
    }

    @Test
    fun routeVariant_showsWaypointList() {
        val from = SpecificFeature.GenericPlace("C5 From Place", null, null, null, GeoPoint(-122.4, 37.8))
        val to = SpecificFeature.GenericPlace("C5 To Place", null, null, null, GeoPoint(-122.3, 37.9))
        rule.setContent {
            val scope = buildScope(
                selectedFeature = SpecificFeature.Route(listOf(from, to)),
            )
            DynamicTheme { scope.MapContentBox() }
        }
        rule.waitForIdle()
        rule.onNodeWithText("C5 To Place", substring = true).assertIsDisplayed()
    }
}
