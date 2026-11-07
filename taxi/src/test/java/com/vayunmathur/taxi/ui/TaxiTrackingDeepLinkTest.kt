package com.vayunmathur.taxi.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.VehicleInfo
import com.vayunmathur.taxi.platform.deeplink.RideDeepLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deep-link construction, status label/progress mapping, tracking cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiTrackingDeepLinkTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pickup = Place("Home", "1 Main St", LatLng(37.1, -122.1))
    private val dropoff = Place("Work", null, LatLng(37.2, -122.2))
    private val quote = RideQuote(
        Provider.UBER, "uberx-id", "UberX", 1000, 1000, "USD", 5, 20, null, 4,
    )

    @Test
    fun uberWebAndAppUrisCarryTripAndProduct() {
        val web = RideDeepLinks.webUri(Provider.UBER, pickup, dropoff, quote)
        assertTrue(web.startsWith("https://m.uber.com/ul/"))
        assertTrue(web.contains("action=setPickup"))
        assertTrue(web.contains("product_id=uberx-id"))
        assertTrue(web.contains("37.1"))
        val app = RideDeepLinks.appUri(Provider.UBER, pickup, dropoff, null)
        assertTrue(app.startsWith("uber://"))
        assertTrue(!app.contains("product_id"))
    }

    @Test
    fun lyftUrisDefaultRideTypeAndOptionalAddresses() {
        val web = RideDeepLinks.webUri(Provider.LYFT, pickup, dropoff, null)
        assertTrue(web.startsWith("https://lyft.com/ride"))
        assertTrue(web.contains("id=lyft"))
        // Pickup has an address, dropoff does not.
        assertTrue(web.contains("pickup%5Baddress%5D") || web.contains("pickup[address]"))
        val app = RideDeepLinks.appUri(Provider.LYFT, pickup, dropoff, quote.copy(provider = Provider.LYFT))
        assertTrue(app.startsWith("lyft://ridetype"))
        assertTrue(app.contains("id=uberx-id"))
    }

    @Test
    fun statusLabelAndProgressMapping() {
        assertEquals(R.string.status_finding_driver, statusLabel(RideStatus.PENDING, ended = false))
        assertEquals(R.string.status_en_route, statusLabel(RideStatus.ACCEPTED, ended = false))
        assertEquals(R.string.status_en_route, statusLabel(RideStatus.APPROACHING, ended = false))
        assertEquals(R.string.status_arrived, statusLabel(RideStatus.ARRIVED, ended = false))
        assertEquals(R.string.status_on_trip, statusLabel(RideStatus.PICKED_UP, ended = false))
        assertEquals(R.string.ride_complete, statusLabel(RideStatus.COMPLETED, ended = false))
        assertEquals(R.string.ride_ended, statusLabel(RideStatus.CANCELED, ended = false))
        assertEquals(R.string.ride_ended, statusLabel(RideStatus.ACCEPTED, ended = true))
        assertEquals(1f, statusProgress(RideStatus.PENDING, over = true))
        assertEquals(0.15f, statusProgress(RideStatus.PENDING, over = false))
        assertEquals(0.35f, statusProgress(RideStatus.ACCEPTED, over = false))
        assertEquals(0.55f, statusProgress(RideStatus.APPROACHING, over = false))
        assertEquals(0.7f, statusProgress(RideStatus.ARRIVED, over = false))
        assertEquals(0.9f, statusProgress(RideStatus.PICKED_UP, over = false))
        assertEquals(0.1f, statusProgress(RideStatus.UNKNOWN, over = false))
    }

    @Test
    fun statusHeaderAndDriverCardRenderRide() {
        val ride = ActiveRide(
            rideId = "r9", status = RideStatus.APPROACHING, statusRaw = "approaching",
            driver = DriverInfo("Sam", "Driver", null, "+15551234", 4.8),
            vehicle = VehicleInfo("Ford", "Focus", "Blue", "XYZ9", null),
            driverLocation = null, stops = emptyList(), raw = "",
        )
        composeRule.setContent { DriverCard(ride) {} }
        composeRule.onNodeWithText("Sam Driver", substring = true).assertIsDisplayed()
    }
}
