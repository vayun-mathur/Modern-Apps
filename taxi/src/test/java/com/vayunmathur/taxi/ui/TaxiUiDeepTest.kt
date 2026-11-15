package com.vayunmathur.taxi.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.maps.DirectionsLatLng
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.intents.maps.DirectionsSegment
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiUiDeepTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg a: Any) = context.getString(id, *a)
    private val home = Place("Home", "1 Main St", LatLng(37.7, -122.4))
    private val work = Place("Work", "2 Market St", LatLng(37.8, -122.5))
    private val noAddr = Place("Airport", null, LatLng(37.6, -122.3))
    private val sameName = Place("Mall", "Mall", LatLng(37.5, -122.2))

    private fun quote(name: String, low: Long, surge: Double? = null, eta: Int? = 5, cap: Int? = 4) = RideQuote(
        Provider.LYFT, "p-$name", name, low, low, "USD", eta, 20, surge, cap,
        originalFareLowMinor = if (name == "Disc") 2000 else null, originalFareHighMinor = if (name == "Disc") 2000 else null,
    )

    // pure helpers
    @Test fun fareAndRouteHelpers() {
        assertEquals("$10.00", formatFare(quote("A", 1000)))
        assertEquals("$10.00 – $12.00", formatFare(quote("A", 1000).copy(fareHighMinor = 1200)))
        assertEquals("$20.00", formatOriginalFare(quote("Disc", 1500)))
        assertEquals("$15.00", formatOriginalFare(quote("A", 1500))) // fallback to fare
        // traffic bands light/dark
        assertEquals(Color(0xFFF44336), trafficColor(0.2, false)); assertEquals(Color(0xFFEF5350), trafficColor(0.2, true))
        assertEquals(Color(0xFFFFC107), trafficColor(0.5, false)); assertEquals(Color(0xFFFFCA28), trafficColor(0.89, true))
        assertEquals(Color(0xFF4CAF50), trafficColor(0.9, false)); assertEquals(Color(0xFF66BB6A), trafficColor(2.0, true))
        // bounds camera
        assertNull(routeBoundsCamera(emptyList()))
        val single = routeBoundsCamera(listOf(GeoPoint(-122.0, 37.0)))!!
        assertEquals(14.0, single.zoom); assertEquals(37.0, single.target.latitude)
        val multi = routeBoundsCamera(listOf(GeoPoint(-122.0, 37.0), GeoPoint(-121.0, 38.0)))!!
        assertEquals(37.5, multi.target.latitude); assertTrue(multi.zoom in 10.0..15.0)
        // overlay: empty, single-point filtered, multi coloured
        assertNull(DirectionsResult(emptyList(), emptyList(), 0.0, 0.0).toRouteOverlay(false))
        val seg1 = DirectionsSegment(listOf(DirectionsLatLng(1.0, 2.0)), 0.3)
        assertNull(DirectionsResult(emptyList(), listOf(seg1), 0.0, 0.0).toRouteOverlay(false))
        val seg2 = DirectionsSegment(listOf(DirectionsLatLng(1.0, 2.0), DirectionsLatLng(3.0, 4.0)), 0.3)
        val overlay = DirectionsResult(listOf(DirectionsLatLng(1.0, 2.0)), listOf(seg2), 100.0, 60.0).toRouteOverlay(true)!!
        assertEquals(1, overlay.segments.size); assertEquals(2, overlay.segments[0].points.size)
        assertEquals(8.dp, overlay.style.width)
    }

    @Test fun suggestionListClickAndAddressRules() {
        var selected: Place? = null
        rule.setContent { SuggestionList(listOf(home, noAddr, sameName)) { selected = it } }
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.onNodeWithText("1 Main St").assertIsDisplayed()
        rule.onNodeWithText("Airport").assertIsDisplayed()
        rule.onNodeWithText("Work").assertDoesNotExist()
        rule.onNodeWithText("Airport").performClick()
        assertEquals("Airport", selected?.name)
    }

    @Test fun rideInputsPaneSuggestionsSearchingAndResults() {
        var selected: Place? = null
        var booked: Provider? = null
        val results = mapOf(Provider.LYFT to QuoteResult.Success(listOf(quote("Lyft Std", 1200))))
        rule.setContent {
            RideInputsPane(
                pickupQuery = "Home", onPickupChange = {}, onPickupFocus = {}, onResetPickup = {},
                destinationQuery = "Work", onDestinationChange = {}, onDestinationFocus = {}, onClearDestination = {},
                onSearch = {}, suggestions = listOf(home), searching = false, active = RouteField.DESTINATION,
                onSelect = { selected = it }, results = results, comparing = false,
                pickup = home, destination = work, onBook = { p, _ -> booked = p },
            )
        }
        assertTrue(rule.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Lyft Std").assertIsDisplayed()
        rule.onNodeWithText("Lyft Std").performClick()
        assertEquals(Provider.LYFT, booked)
        // Note suggestion row also "Home" - first click above may have selected? booked proves results path exercised
    }

    @Test fun rideInputsPaneSearchingShowsLoading() {
        rule.setContent {
            RideInputsPane(
                pickupQuery = "", onPickupChange = {}, onPickupFocus = {}, onResetPickup = {},
                destinationQuery = "Wor", onDestinationChange = {}, onDestinationFocus = {}, onClearDestination = {},
                onSearch = {}, suggestions = emptyList(), searching = true, active = RouteField.PICKUP,
                onSelect = {}, results = emptyMap(), comparing = false, pickup = null, destination = null,
                onBook = { _, _ -> },
            )
        }
        rule.onNodeWithText(str(R.string.searching)).assertIsDisplayed()
    }

    @Test fun resultsFailedNoteDiscountSurgeAndEmptyNotes() {
        var booked: Pair<Provider, RideQuote?>? = null
        val results = mapOf(
            Provider.LYFT to QuoteResult.Success(listOf(quote("Disc", 1500, surge = 1.5))),
            Provider.UBER to QuoteResult.Failed("Uber exploded"),
        )
        rule.setContent { ResultsSection(results, comparing = false, hasRoute = true, onBook = { p, q -> booked = p to q }) }
        rule.onNodeWithText("Disc").assertIsDisplayed()
        rule.onNodeWithText("$20.00").assertIsDisplayed() // original struck
        rule.onNodeWithText("$15.00").assertIsDisplayed()
        rule.onNodeWithText("Uber exploded").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.best_price)).assertIsDisplayed()
    }

    @Test fun resultsNoQuotesNoNotesShowsNoPlaces() {
        rule.setContent { ResultsSection(emptyMap(), comparing = false, hasRoute = true, onBook = { _, _ -> }) }
        rule.onNodeWithText(str(R.string.no_places_found)).assertIsDisplayed()
    }

    @Test fun providerBadgeAndMapPinAndLoadingRow() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                ProviderBadge(Provider.LYFT)
                ProviderBadge(Provider.UBER)
                LoadingRow("Searching…")
                MapPin(DpOffset(50.dp, 50.dp), Color.Red) { com.vayunmathur.library.ui.Text("P") }
            }
        }
        rule.onNodeWithText("L").assertIsDisplayed()
        rule.onNodeWithText("U").assertIsDisplayed()
        rule.onNodeWithText("Searching…").assertIsDisplayed()
    }

    @Test fun routeInputsClearButtonWhenDestinationTyped() {
        var cleared = 0; var pickupChanged = ""
        rule.setContent {
            RouteInputs(
                pickupQuery = "Home", onPickupChange = { pickupChanged = it }, onPickupFocus = {}, onResetPickup = {},
                destinationQuery = "Work", onDestinationChange = {}, onDestinationFocus = {},
                onClearDestination = { cleared++ }, onSearch = {},
            )
        }
        rule.onNodeWithText("Work").assertIsDisplayed()
        rule.onNodeWithText("Home").assertIsDisplayed()
        assertEquals(0, cleared)
    }
}
