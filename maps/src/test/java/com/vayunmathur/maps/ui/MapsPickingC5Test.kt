package com.vayunmathur.maps.ui
import com.vayunmathur.maps.platform.SearchResult

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapMarker
import com.vayunmathur.library.map.Projection
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.map.MapHit
import com.vayunmathur.maps.ui.map.adminUpgradeFor
import com.vayunmathur.maps.ui.map.openVehicleTrip
import com.vayunmathur.maps.ui.map.pickPinOnCpu
import com.vayunmathur.maps.ui.map.pickVehicle
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import com.vayunmathur.maps.platform.TransitStopsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * C5 CPU picking over a live projection: vehicle sprites, pin features,
 * vehicle-trip opening, and the admin-label upgrade match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsPickingC5Test {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun projection(): Projection {
        val camera = CameraState().apply {
            position = position.copy(target = GeoPoint(-122.4194, 37.7749), zoom = 14.0)
        }
        rule.setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize()) {
                    VectorMap(cameraState = camera, modifier = Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        return assertNotNull(camera.projection, "map measured a viewport")
    }

    @Test
    fun pickVehicle_nearestWithinSlopAndNoneOutside() {
        val projection = projection()
        val near = MapMarker(1L, GeoPoint(-122.4194, 37.7749), icon = 0)
        val far = MapMarker(2L, GeoPoint(-122.30, 37.70), icon = 0)
        val centre = projection.screenLocationFromPosition(near.position)
        assertEquals(1L, pickVehicle(listOf(near, far), projection, centre)?.id)
        assertEquals(1L, pickVehicle(listOf(far, near), projection, centre)?.id)
        // A tap far from both sprites picks nothing.
        assertNull(
            pickVehicle(
                listOf(near, far), projection,
                DpOffset(centre.x + 400.dp, centre.y + 400.dp),
            ),
        )
        assertNull(pickVehicle(emptyList(), projection, centre))
    }

    @Test
    fun pickPinOnCpu_hitsSavedSearchAndParkingPins() = runBlocking {
        val projection = projection()
        val centreGeo = GeoPoint(-122.4194, 37.7749)
        val centre = projection.screenLocationFromPosition(centreGeo)
        val saved = listOf(SavedPlace("C5 Picked Home", 37.7749, -122.4194))
        val hit = pickPinOnCpu(
            offset = centre, projection = projection,
            searchResults = emptyList(), savedPlaces = saved,
            parkingSpot = null, familyMembers = emptyList(), transitEnabled = false,
        )
        assertTrue(hit is MapHit.Place, "saved pin hit, was $hit")
        assertEquals(
            "C5 Picked Home",
            ((hit as MapHit.Place).feature as SpecificFeature.GenericPlace).name,
        )

        val searched = listOf(SearchResult("r9", "C5 Picked Cafe", null, 37.7749, -122.4194, null))
        val searchHit = pickPinOnCpu(
            offset = centre, projection = projection,
            searchResults = searched, savedPlaces = emptyList(),
            parkingSpot = null, familyMembers = emptyList(), transitEnabled = false,
        )
        assertTrue(searchHit is MapHit.Place, "search pin hit, was $searchHit")

        val parkingHit = pickPinOnCpu(
            offset = centre, projection = projection,
            searchResults = emptyList(), savedPlaces = emptyList(),
            parkingSpot = ParkingSpot(37.7749, -122.4194, 1L, null),
            familyMembers = emptyList(), transitEnabled = false,
        )
        assertTrue(parkingHit is MapHit.Parking, "parking pin hit, was $parkingHit")

        // Nothing near the opposite corner.
        assertNull(
            pickPinOnCpu(
                offset = DpOffset(centre.x + 400.dp, centre.y + 400.dp),
                projection = projection,
                searchResults = searched, savedPlaces = saved,
                parkingSpot = null, familyMembers = emptyList(), transitEnabled = false,
            ),
        )
    }

    @Test
    fun openVehicleTrip_opensTripForHitSpriteOnly() = runBlocking {
        val projection = projection()
        val marker = MapMarker(42L, GeoPoint(-122.4194, 37.7749), icon = 0)
        val centre = projection.screenLocationFromPosition(marker.position)
        val viewModel = SelectedFeatureViewModel(app)
        val transitViewModel = TransitStopsViewModel(app)
        assertTrue(
            openVehicleTrip(listOf(marker), projection, centre, viewModel, transitViewModel, null),
        )
        // A miss leaves everything untouched.
        assertTrue(
            !openVehicleTrip(
                listOf(marker), projection,
                DpOffset(centre.x + 400.dp, centre.y + 400.dp),
                viewModel, transitViewModel, null,
            ),
        )
    }

    @Test
    fun adminUpgradeFor_matchesAdminLabelsByNormalisedName() {
        val place = SpecificFeature.GenericPlace("san francisco", null, null, null, GeoPoint(-122.4, 37.8))
        val admin = SpecificFeature.Admin2Label(null, "San Francisco", GeoPoint(-122.4, 37.8), 8)
        val other = SpecificFeature.GenericPlace("San Francisco", null, null, null, GeoPoint(-122.4, 37.8))
        assertEquals(admin, adminUpgradeFor(place, listOf(other, admin)))
        assertNull(adminUpgradeFor(place, listOf(other)))
        assertNull(
            adminUpgradeFor(
                SpecificFeature.GenericPlace("  ", null, null, null, GeoPoint(0.0, 0.0)),
                listOf(admin),
            ),
        )
    }
}
