package com.vayunmathur.taxi.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Models' derived values plus the quote-results and route-inputs UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    private fun quote(provider: Provider, name: String, low: Long, high: Long) = RideQuote(
        provider = provider, productId = "p-$name", displayName = name, fareLowMinor = low,
        fareHighMinor = high, currency = "USD", pickupEtaMinutes = 5, tripDurationMinutes = 20,
        surgeMultiplier = null, capacity = 4,
    )

    @Test
    fun fareFormattingExactRangeAndDiscount() {
        assertEquals("$12.34", formatFare(quote(Provider.UBER, "UberX", 1234, 1234)))
        assertEquals("$10.00 – $15.00", formatFare(quote(Provider.LYFT, "Lyft", 1000, 1500)))
        val discounted = quote(Provider.LYFT, "Lyft", 800, 800)
            .copy(originalFareLowMinor = 1000, originalFareHighMinor = 1000)
        assertTrue(discounted.hasDiscount)
        assertEquals("$10.00", formatOriginalFare(discounted))
        assertFalse(quote(Provider.UBER, "U", 1, 1).hasDiscount)
    }

    @Test
    fun rideStatusWireMappingAndLifecycle() {
        assertEquals(RideStatus.PICKED_UP, RideStatus.fromWire("pickedUp"))
        assertEquals(RideStatus.ACCEPTED, RideStatus.fromWire("ACCEPTED"))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire("bogus"))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire(null))
        assertEquals(RideStatus.UNKNOWN, RideStatus.fromWire(" "))
        assertTrue(RideStatus.APPROACHING.isPrePickup)
        assertTrue(RideStatus.PICKED_UP.isActive)
        assertFalse(RideStatus.PICKED_UP.isPrePickup)
        assertTrue(RideStatus.COMPLETED.isTerminal)
        assertFalse(RideStatus.ACCEPTED.isTerminal)
    }

    @Test
    fun driverVehicleCardAndActiveRideDerived() {
        assertEquals("Ada Lovelace", DriverInfo("Ada", "Lovelace", null, null, 4.9).displayName)
        assertEquals("", DriverInfo(null, null, null, null, null).displayName)
        assertEquals("Red Toyota Camry", VehicleInfo("Toyota", "Camry", "Red", "ABC123", null).description)
        assertEquals("411111", NewCard("4111111111111111", 12, 2030, "123", "94101").bin.take(6))
        assertEquals("1111", NewCard("4111111111111111", 12, 2030, "123", "94101").last4)
        val ride = ActiveRide(
            rideId = "r1", status = RideStatus.APPROACHING, statusRaw = "approaching",
            driver = null, vehicle = null, driverLocation = null,
            stops = listOf(
                RideStopInfo(LatLng(0.0, 0.0), "Home", "pickup", 120, completed = false),
                RideStopInfo(LatLng(1.0, 1.0), "Work", "dropoff", 600, completed = false),
            ),
            raw = "{}",
        )
        assertEquals(120, ride.pickupEtaSeconds)
        assertTrue(ride.hasContent)
        assertTrue(ride.stops[0].isPickup)
        assertTrue(ride.stops[1].isDropoff)
        assertNull(ActiveRide(null, RideStatus.UNKNOWN, null, null, null, null, emptyList(), "").pickupEtaSeconds)
        assertFalse(ActiveRide(null, RideStatus.UNKNOWN, null, null, null, null, emptyList(), "").hasContent)
    }

    @Test
    fun resultsComparingAndEmptyStates() {
        composeRule.setContent {
            ResultsSection(emptyMap(), comparing = true, hasRoute = false, onBook = { _, _ -> })
        }
        composeRule.onNodeWithText(str(R.string.comparing_fares)).assertIsDisplayed()
    }

    @Test
    fun resultsEmptyPromptWithoutRoute() {
        composeRule.setContent {
            ResultsSection(emptyMap(), comparing = false, hasRoute = false, onBook = { _, _ -> })
        }
        composeRule.onNodeWithText(str(R.string.enter_destination)).assertIsDisplayed()
    }

    @Test
    fun resultsQuotesSortedCheapestFirstAndBookFires() {
        var booked: Pair<Provider, RideQuote?>? = null
        val results = mapOf(
            Provider.UBER to QuoteResult.Success(listOf(quote(Provider.UBER, "UberX", 2000, 2000))),
            Provider.LYFT to QuoteResult.Success(listOf(quote(Provider.LYFT, "Lyft Std", 1500, 1800))),
        )
        composeRule.setContent {
            ResultsSection(results, comparing = false, hasRoute = true, onBook = { p, q -> booked = p to q })
        }
        composeRule.onNodeWithText("Lyft Std").assertIsDisplayed()
        composeRule.onNodeWithText("$15.00 – $18.00").assertIsDisplayed()
        composeRule.onNodeWithText("Lyft Std").performClick()
        assertEquals(Provider.LYFT, booked?.first)
        assertEquals("Lyft Std", booked?.second?.displayName)
    }

    @Test
    fun resultsNotSignedInNoteFiresBookWithNullQuote() {
        var booked: Pair<Provider, RideQuote?>? = null
        val results = mapOf(Provider.UBER to QuoteResult.NotSignedIn)
        composeRule.setContent {
            ResultsSection(results, comparing = false, hasRoute = true, onBook = { p, q -> booked = p to q })
        }
        composeRule.onNodeWithText(str(R.string.connect_prompt, "Uber")).assertIsDisplayed()
        assertEquals(null, booked)
    }

    @Test
    fun routeInputsReportTypingAndClearVisibility() {
        var pickup = ""
        var destination = ""
        composeRule.setContent {
            RouteInputs(
                pickupQuery = pickup, onPickupChange = { pickup = it }, onPickupFocus = {},
                onResetPickup = {}, destinationQuery = destination,
                onDestinationChange = { destination = it }, onDestinationFocus = {},
                onClearDestination = { destination = "" }, onSearch = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.pickup_label)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.destination)).assertIsDisplayed()
        assertEquals("", pickup)
        assertEquals("", destination)
    }
}
