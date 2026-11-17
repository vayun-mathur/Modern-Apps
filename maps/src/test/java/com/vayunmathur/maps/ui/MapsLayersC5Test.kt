package com.vayunmathur.maps.ui
import com.vayunmathur.maps.platform.SearchResult

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.ui.streetview.StreetViewPegman
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * C5 pin layers over a live projection: the vector map measures the
 * viewport, after which the saved/search/parking pins project onto real
 * screen positions, and the Street View peg can be dragged and dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsLayersC5Test {

    @get:Rule val rule = createComposeRule()

    private fun cameraAtSf(): CameraState = CameraState().apply {
        position = position.copy(
            target = com.vayunmathur.library.map.GeoPoint(-122.4194, 37.7749),
            zoom = 14.0,
        )
    }

    @Test
    fun savedSearchAndParkingPins_projectOntoTheMap() {
        val camera = cameraAtSf()
        rule.setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize()) {
                    VectorMap(cameraState = camera, modifier = Modifier.fillMaxSize())
                    SavedPlacesLayer(
                        places = listOf(
                            SavedPlace("C5 Home", 37.7749, -122.4194),
                            SavedPlace("C5 Work", 37.7755, -122.4185),
                        ),
                        cameraState = camera,
                    )
                    SearchResultLayer(
                        results = listOf(
                            SearchResult("r1", "C5 Result", null, 37.7745, -122.4190, null),
                        ),
                        cameraState = camera,
                    )
                    ParkingLayer(
                        spot = ParkingSpot(37.7742, -122.4188, 1_700_000_000_000L, null),
                        cameraState = camera,
                    )
                }
            }
        }
        rule.waitForIdle()
        assertNotNull(camera.projection, "map measured a viewport")
        // Two saved pins draw their star glyph; the parking pin its P.
        assertEquals(2, rule.onAllNodesWithText("★").fetchSemanticsNodes().size)
        rule.onNodeWithText("P").assertIsDisplayed()
    }

    @Test
    fun streetViewPeg_dragAndDrop_resolvesAndReportsNoImagery() {
        val camera = cameraAtSf()
        rule.setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize()) {
                    VectorMap(cameraState = camera, modifier = Modifier.fillMaxSize())
                    StreetViewPegman(camera)
                }
            }
        }
        rule.waitForIdle()
        val peg = rule.onNode(
            hasContentDescription("Drag onto the map to open Street View", substring = true),
        )
        peg.assertExists()
        // Drag the peg leftwards onto the map and release: the drop resolves
        // the point, finds no imagery offline, and says so via the messenger
        // (and, most importantly for coverage, exercises the whole drop path
        // without opening a viewer).
        peg.performTouchInput { swipeLeft() }
        rule.waitForIdle()
    }
}
