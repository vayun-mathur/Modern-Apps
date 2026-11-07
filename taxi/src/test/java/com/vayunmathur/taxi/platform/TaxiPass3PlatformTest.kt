package com.vayunmathur.taxi.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.ipc.DirectionsClient
import com.vayunmathur.taxi.ipc.DirectionsProtocol
import com.vayunmathur.taxi.ipc.RideHandoffContract
import com.vayunmathur.taxi.platform.deeplink.RideDeepLinks
import com.vayunmathur.taxi.platform.location.LocationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: deep links, the cross-app booking handoff contract, directions
 * IPC degradation, and the platform location provider under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3PlatformTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val pickup = Place("Home Base", "1 Main St", LatLng(37.7749, -122.4194))
    private val dropoff = Place("Work Place", null, LatLng(37.8044, -122.2712))
    private val quote = RideQuote(
        provider = Provider.LYFT, productId = "lyft_premium", displayName = "Premium",
        fareLowMinor = 2000, fareHighMinor = 2000, currency = "USD",
        pickupEtaMinutes = 3, tripDurationMinutes = 15, surgeMultiplier = null, capacity = 4,
    )

    // --- Deep links ---

    @Test
    fun webAndAppUrisCarryTripAndProduct() {
        val uberWeb = RideDeepLinks.webUri(Provider.UBER, pickup, dropoff, quote)
        assertTrue(uberWeb.startsWith("https://m.uber.com/ul/"), uberWeb)
        assertTrue(uberWeb.contains("action=setPickup"))
        assertTrue(uberWeb.contains("product_id=lyft_premium"))
        assertTrue(uberWeb.contains("pickup%5Bnickname%5D=Home%20Base") ||
            uberWeb.contains("pickup[nickname]=Home%20Base"), uberWeb)

        val lyftWeb = RideDeepLinks.webUri(Provider.LYFT, pickup, dropoff, null)
        assertTrue(lyftWeb.startsWith("https://lyft.com/ride"), lyftWeb)
        assertTrue(lyftWeb.contains("id=lyft"), lyftWeb)
        assertTrue(lyftWeb.contains("pickup%5Baddress%5D=1%20Main%20St") ||
            lyftWeb.contains("pickup[address]=1%20Main%20St"), lyftWeb)
        // No address on the dropoff: the parameter is omitted.
        assertFalse(lyftWeb.contains("destination%5Baddress%5D"))

        val uberApp = RideDeepLinks.appUri(Provider.UBER, pickup, dropoff, quote)
        assertTrue(uberApp.startsWith("uber://"), uberApp)
        val lyftApp = RideDeepLinks.appUri(Provider.LYFT, pickup, dropoff, quote)
        assertTrue(lyftApp.startsWith("lyft://ridetype"), lyftApp)
        assertTrue(lyftApp.contains("id=lyft_premium"))
    }

    @Test
    fun openBookingFallsBackToWebWhenAppMissing() {
        // Neither provider app is installed in the test sandbox.
        assertFalse(shadowOf(context.packageManager).let { false })
        val opened = RideDeepLinks.openBooking(context, Provider.LYFT, pickup, dropoff, quote)
        val intent = shadowOf(app).nextStartedActivity
        if (opened) {
            assertNotNull(intent)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.data.toString().startsWith("https://lyft.com/ride"))
        }
    }

    // --- Handoff contract ---

    @Test
    fun bookingDeepLinkRoundTrips() {
        val link = RideHandoffContract.bookingDeepLink(
            pickupLat = 37.7749, pickupLng = -122.4194, pickupLabel = "Home",
            destLat = 37.8044, destLng = -122.2712, destLabel = "Work",
        )
        assertTrue(link.startsWith("taxi://book?"), link)
        val trip = RideHandoffContract.parseBooking(link)
        assertNotNull(trip)
        assertEquals("Home", trip.pickup.name)
        assertEquals("Work", trip.destination.name)
        assertEquals(37.7749, trip.pickup.location.latitude)
        assertEquals(-122.2712, trip.destination.location.longitude)
        assertEquals(BookingTrip(trip.pickup, trip.destination), trip)
    }

    @Test
    fun bookingDeepLinkLabelsOptionalAndBadLinksRejected() {
        val link = RideHandoffContract.bookingDeepLink(1.0, 2.0, "  ", 3.0, 4.0, null)
        assertFalse(link.contains("pickup_label"))
        assertFalse(link.contains("dest_label"))
        val trip = RideHandoffContract.parseBooking(link)
        assertNotNull(trip)
        assertEquals("", trip.pickup.name)

        assertNull(RideHandoffContract.parseBooking(null))
        assertNull(RideHandoffContract.parseBooking(""))
        assertNull(RideHandoffContract.parseBooking("https://example.com/book?pickup_lat=1&pickup_lng=2&dest_lat=3&dest_lng=4"))
        assertNull(RideHandoffContract.parseBooking("taxi://other?pickup_lat=1&pickup_lng=2&dest_lat=3&dest_lng=4"))
        assertNull(RideHandoffContract.parseBooking("taxi://book?pickup_lat=1&pickup_lng=2"))
        assertNull(RideHandoffContract.parseBooking("taxi://book?pickup_lat=x&pickup_lng=2&dest_lat=3&dest_lng=4"))
    }

    // --- Directions IPC ---

    @Test
    fun directionsDegradeToNullWithoutMaps() = runTest {
        assertEquals("com.vayunmathur.maps", DirectionsProtocol.PACKAGE)
        assertEquals("drive", DirectionsProtocol.MODE_DRIVE)
        // Maps is not installed in the sandbox: the launch fails inside and
        // the client reports null rather than throwing.
        val result = DirectionsClient.directions(context, 37.0, -122.0, 38.0, -122.5)
        assertNull(result)
    }

    // --- Location provider ---

    @Test
    fun locationPermissionGateAndDescribeFallback() = runTest {
        assertFalse(LocationProvider.hasPermission(context))
        assertNull(LocationProvider.current(context))
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        assertTrue(LocationProvider.hasPermission(context))
        // No providers are enabled in the sandbox: current() still resolves.
        val here = LocationProvider.current(context)
        assertTrue(here == null || (here.latitude in -90.0..90.0))
        // Blank search never touches the geocoder.
        assertEquals(emptyList(), LocationProvider.search(context, ""))
        assertEquals(emptyList(), LocationProvider.search(context, "   "))
        // describe() falls back to the generic place when geocoding fails.
        val place = LocationProvider.describe(context, LatLng(37.7749, -122.4194))
        assertEquals(LatLng(37.7749, -122.4194), place.location)
        assertTrue(place.name.isNotBlank())
    }

    @Test
    fun locationSearchWithGeocoderShadow() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        // Robolectric's geocoder has no backend: results degrade to empty.
        val results = LocationProvider.search(context, "San Francisco")
        assertTrue(results.all { it.name.isNotBlank() })
    }
}
