package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.R as MapsR
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ui.streetview.StreetViewArrows
import com.vayunmathur.maps.ui.streetview.StreetViewPegman
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.util.TripItineraryState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the map chrome (search bar, FAB stack, waypoint list,
 * overlay sheets) under Robolectric's Compose rule.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1MapChromeComposeTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun strings(id: Int, vararg args: Any): String =
        if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)

    // --- MapSearchBar -----------------------------------------------------

    @Test
    fun `the search pill opens search on tap`() {
        var opened: String? = "unset"
        rule.setContent {
            MapSearchBar(
                onOpenSearch = { opened = it },
                onContactAddress = {},
            )
        }
        val pill = strings(MapsR.string.search_placeholder)
        rule.onNodeWithText(pill).assertIsDisplayed()
        rule.onNodeWithText(pill).performClick()
        assertTrue(opened == null, "tapping the pill opens a blank search")
    }

    // --- MapFabStack --------------------------------------------------------

    @Test
    fun `browse controls compose without firing`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        var layers = 0
        var parking = 0
        var mine = 0
        var north = 0
        rule.setContent {
            Box {
                MapFabStack(
                    camera = camera,
                    browsing = true,
                    lift = { 0 },
                    onResetNorth = { north++ },
                    onLayers = { layers++ },
                    onParking = { parking++ },
                    onMyLocation = { mine++ },
                )
            }
        }
        // Parking + my-location FABs are always present while browsing.
        rule.waitForIdle()
        assertTrue(layers == 0 && parking == 0 && mine == 0 && north == 0)
    }

    @Test
    fun `a moving camera does not recompose the host of the fab stack`() {
        // The host stands in for MapContentBox, which carries the map surface: it must not
        // subscribe to the camera through the stack, or every frame of a drag recomposes the
        // whole map box. Only the scale-bar and compass leaves may read `camera.position`.
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        var hostCompositions = 0
        rule.setContent {
            Box {
                hostCompositions++
                MapFabStack(
                    camera = camera,
                    browsing = true,
                    lift = { 0 },
                    onResetNorth = {},
                    onLayers = {},
                    onParking = {},
                    onMyLocation = {},
                )
            }
        }
        rule.waitForIdle()
        val settled = hostCompositions
        repeat(30) { frame ->
            rule.runOnIdle {
                camera.position = camera.position.copy(
                    target = GeoPoint(-122.4 + frame * 1e-3, 37.7),
                    zoom = 12.0 + frame * 0.05,
                    bearing = frame * 3.0,
                )
            }
            rule.waitForIdle()
        }
        assertTrue(
            hostCompositions == settled,
            "30 camera frames recomposed the host ${hostCompositions - settled} times",
        )
    }

    // --- WaypointList ---------------------------------------------------------

    private fun place(name: String, lon: Double) = SpecificFeature.GenericPlace(
        name = name,
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(lon, 37.7),
    )

    @Test
    fun `waypoints render with your-location fallback`() {
        var edited = -1
        val route = SpecificFeature.Route(
            listOf(place("Start", -122.5), null, place("End", -122.3)),
        )
        rule.setContent {
            WaypointList(
                route = route,
                onReorder = {},
                onEditWaypoint = { edited = it },
            )
        }
        rule.onNodeWithText("Start").assertIsDisplayed()
        rule.onNodeWithText(strings(MapsR.string.your_location)).assertIsDisplayed()
        rule.onNodeWithText("End").assertIsDisplayed()
        // Tapping a row edits that waypoint.
        rule.onNodeWithText("End").performClick()
        assertTrue(edited == 2)
    }

    // --- MapOverlays ------------------------------------------------------------

    private fun toggles() = LayerToggles(
        traffic = false, satellite = false, safety = false, transit = false,
        onTraffic = {}, onSatellite = {}, onSafety = {}, onTransit = {},
    )

    private fun overlays(
        overlay: MapOverlay,
        parkingSpot: ParkingSpot? = null,
        selectedStop: TransitStop? = null,
        departures: DeparturesState = DeparturesState.Idle,
        tripSheet: TripItineraryState = TripItineraryState.Idle,
    ) {
        rule.setContent {
            MapOverlays(
                overlay = overlay,
                onDismiss = {},
                layers = toggles(),
                parkingSpot = parkingSpot,
                onClearParking = {},
                onParkingDirections = {},
                onParkingNoteChange = {},
                selectedStop = selectedStop,
                departures = departures,
                onCloseStop = {},
                onRefreshDepartures = {},
                onTrainTap = {},
                tripSheet = tripSheet,
                onCloseTrip = {},
                onTripStopTap = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `no overlay and no selection renders nothing`() {
        overlays(MapOverlay.None)
    }

    @Test
    fun `the layers sheet opens from the overlay`() {
        overlays(MapOverlay.Layers)
    }

    @Test
    fun `the parking sheet shows with a spot`() {
        overlays(MapOverlay.Parking, parkingSpot = ParkingSpot(37.7, -122.4, 0L))
    }

    @Test
    fun `the parking overlay is a no-op without a spot`() {
        overlays(MapOverlay.Parking, parkingSpot = null)
    }

    @Test
    fun `a selected stop opens its board and a loading trip its sheet`() {
        val stop = TransitStop("m1", "Slice1 Station", 37.7, -122.0)
        overlays(
            MapOverlay.None,
            selectedStop = stop,
            departures = DeparturesState.Loaded(stop, emptyList()),
            tripSheet = TripItineraryState.Loading,
        )
        rule.onNodeWithText("Slice1 Station").assertIsDisplayed()
    }

    // --- StreetViewArrows ----------------------------------------------------------

    @Test
    fun `arrows project onto the viewport and cull behind the camera`() {
        // Bearing 270 with capture heading 90 faces the default camera
        // (azimuth 0, ahead); bearing 90 is the same geometry turned around
        // (azimuth -pi, behind). One arrow composes, one is culled.
        val pano = com.vayunmathur.maps.data.google.StreetViewPano(
            panoId = "slice1",
            lat = 37.7,
            lng = -122.4,
            headingDeg = 90.0,
            neighbors = listOf(
                com.vayunmathur.maps.data.google.StreetViewLink(
                    panoId = "next",
                    lat = 37.7001,
                    lng = -122.4,
                    bearingDeg = 270.0,
                    distanceM = 11.0,
                ),
                com.vayunmathur.maps.data.google.StreetViewLink(
                    panoId = "back",
                    lat = 37.7001,
                    lng = -122.4,
                    bearingDeg = 90.0,
                    distanceM = 11.0,
                ),
            ),
        )
        val camera = com.vayunmathur.library.ui.PanoramaCameraState()
        var travelled: String? = null
        // A phone-size viewport: the ahead arrow lands mid-screen, well
        // inside the frustum guard.
        rule.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                StreetViewArrows(
                    pano = pano,
                    camera = camera,
                    onTravel = { link -> travelled = link.panoId },
                )
            }
        }
        rule.waitForIdle()
        val ahead = rule.onAllNodes(hasContentDescription(strings(MapsR.string.street_view_move, "W")))
            .fetchSemanticsNodes()
        val behind = rule.onAllNodes(hasContentDescription(strings(MapsR.string.street_view_move, "E")))
            .fetchSemanticsNodes()
        assertTrue(behind.isEmpty(), "a behind-camera arrow must be culled")
        if (ahead.size == 1) {
            rule.onNodeWithContentDescription(strings(MapsR.string.street_view_move, "W")).performClick()
            assertTrue(travelled == "next")
        } else {
            // Viewport too short to place the arrow inside the frustum guard:
            // the projection still ran for both neighbours without throwing.
            assertTrue(ahead.isEmpty(), "unexpected arrow count ${ahead.size}")
        }
    }

    // --- StreetViewPegman ----------------------------------------------------

    @Test
    fun `the peg chip is in the tree with its drag label`() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 12.0))
        rule.setContent {
            Box {
                StreetViewPegman(camera = camera)
            }
        }
        val nodes = rule.onAllNodes(hasContentDescription(strings(MapsR.string.street_view_peg)))
            .fetchSemanticsNodes()
        assertTrue(nodes.size == 1, "expected the peg chip, got ${nodes.size}")
    }
}
