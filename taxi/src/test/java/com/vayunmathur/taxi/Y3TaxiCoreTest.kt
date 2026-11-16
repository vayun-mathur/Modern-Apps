package com.vayunmathur.taxi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RemoteImageFetchers
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.data.uber.UberSession
import com.vayunmathur.taxi.intents.RideEstimateIntent
import com.vayunmathur.taxi.ipc.RideHandoffContract
import com.vayunmathur.taxi.network.uber.UberProvider
import com.vayunmathur.taxi.platform.location.LocationProvider
import com.vayunmathur.taxi.provider.QuoteRepository
import com.vayunmathur.taxi.provider.RideProvider
import com.vayunmathur.taxi.ui.TaxiRideWideLayout
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3TaxiCoreTest {
    @get:Rule val compose = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val pickup = Place("Home", "1 Main", LatLng(37.1, -122.1))
    private val dropoff = Place("Work", null, LatLng(37.2, -122.2))

    @BeforeTest fun clean() { runBlocking { LyftTokenStore(ctx).clear(); UberSession(ctx).clear() } }

    @Test fun handoffRoundTripAndRejections() {
        val link = RideHandoffContract.bookingDeepLink(37.1, -122.1, "Home", 37.2, -122.2, "  ")
        assertTrue(link.startsWith("taxi://book?"))
        assertTrue(link.contains("pickup_label=Home")); assertFalse(link.contains("dest_label"))
        val trip = RideHandoffContract.parseBooking(link)
        assertNotNull(trip); assertEquals(37.1, trip.pickup.location.latitude); assertEquals("Home", trip.pickup.name)
        assertEquals("", trip.destination.name)
        assertNull(RideHandoffContract.parseBooking(null))
        assertNull(RideHandoffContract.parseBooking("https://book?pickup_lat=1&pickup_lng=2&dest_lat=3&dest_lng=4"))
        assertNull(RideHandoffContract.parseBooking("taxi://other?pickup_lat=1&pickup_lng=2&dest_lat=3&dest_lng=4"))
        assertNull(RideHandoffContract.parseBooking("taxi://book?pickup_lat=x&pickup_lng=2&dest_lat=3&dest_lng=4"))
        assertNull(RideHandoffContract.parseBooking("taxi://book?pickup_lat=1"))
        val blankLabels = RideHandoffContract.parseBooking("taxi://book?pickup_lat=1&pickup_lng=2&dest_lat=3&dest_lng=4&pickup_label=%20&dest_label=Dest")
        assertEquals("", blankLabels!!.pickup.name); assertEquals("Dest", blankLabels.destination.name)
    }

    @Test fun uberProviderSignedOutAndBookingUri() : Unit = runBlocking {
        val p = UberProvider(ctx)
        assertEquals(Provider.UBER, p.provider); assertFalse(p.isSignedIn())
        assertEquals(QuoteResult.NotSignedIn, p.quotes(pickup, dropoff))
        assertTrue(p.bookingUri(pickup, dropoff, null).startsWith("https://m.uber.com"))
        UberSession(ctx).setSignedIn(true); assertTrue(p.isSignedIn())
        val q = p.quotes(pickup, dropoff); assertTrue(q is QuoteResult.Failed && q.message.contains("captured"))
    }

    @Test fun uberSessionAuthMarkers() {
        assertTrue(UberSession.looksAuthenticated("https://m.uber.com/go/home"))
        assertFalse(UberSession.looksAuthenticated("https://m.uber.com/login"))
        assertFalse(UberSession.looksAuthenticated("https://auth.uber.com/login"))
        assertFalse(UberSession.looksAuthenticated("http://m.uber.com/go"))
        assertFalse(UberSession.looksAuthenticated("https://example.com/"))
    }

    @Test fun remoteImageFetcherRejectsNonHttp() : Unit = runBlocking {
        assertNull(RemoteImageFetchers.network(null, ctx))
        assertNull(RemoteImageFetchers.network(42, ctx))
        assertNull(RemoteImageFetchers.network("ftp://x/y.png", ctx))
        assertNull(RemoteImageFetchers.network(Uri.parse("file:///tmp/x.png"), ctx))
        assertNull(RemoteImageFetchers.network(Uri.parse("content://media/1"), ctx))
    }

    @Test fun locationProviderWithoutPermission() : Unit = runBlocking {
        assertFalse(LocationProvider.hasPermission(ctx))
        assertNull(LocationProvider.current(ctx))
        assertEquals(emptyList(), LocationProvider.search(ctx, "   "))
        assertEquals(emptyList(), LocationProvider.search(ctx, ""))
    }

    @Test fun quoteRepositoryNotSignedInIsolates() : Unit = runBlocking {
        val res = QuoteRepository.quotes(ctx, pickup, dropoff)
        assertEquals(setOf(Provider.LYFT), res.keys)
        assertTrue(res[Provider.LYFT] is QuoteResult.NotSignedIn || res[Provider.LYFT] is QuoteResult.Failed)
        val refreshed = QuoteRepository.refresh(ctx, pickup, dropoff, emptyMap())
        assertEquals(setOf(Provider.LYFT), refreshed.keys); assertNotNull(refreshed[Provider.LYFT])
        val refreshed2 = QuoteRepository.refresh(ctx, pickup, dropoff, res)
        assertNotNull(refreshed2[Provider.LYFT])
    }

    private class MinimalProvider : RideProvider {
        override val provider = Provider.UBER
        override suspend fun isSignedIn() = true
        override suspend fun quotes(p: Place, d: Place) = QuoteResult.NotSignedIn
        override fun bookingUri(p: Place, d: Place, q: RideQuote?) = "x://y"
    }

    @Test fun rideProviderDefaultsAreUnsupported() : Unit = runBlocking {
        val p = MinimalProvider()
        assertEquals(PaymentMethodsResult.Unsupported, p.paymentMethods())
        assertEquals(PaymentActionResult.Unsupported, p.setDefaultPaymentMethod("a"))
        assertEquals(PaymentActionResult.Unsupported, p.removePaymentMethod("a"))
        assertEquals(AddCardResult.Unsupported, p.addCard(NewCard("4242", 1, 2030, "123", "12345"), true))
        val quote = RideQuote(Provider.UBER, "id", "X", 100, 100, "USD", 1, 1, null, 4)
        assertEquals(BookingResult.Unsupported, p.createRide(quote, pickup, dropoff, null, null, false))
        assertEquals(RideStatusResult.Unsupported, p.activeRide())
        assertNull(p.driverLocation("r")); assertEquals(CancelResult.Unsupported, p.cancelRide("r"))
        assertTrue(ChargeAccount("1", null, "Visa", true).isDefault)
    }

    @Test fun rideEstimateUnavailableWhenNoQuote() : Unit = runBlocking {
        val intent = RideEstimateIntent()
        val req = com.vayunmathur.library.intents.taxi.RideEstimateRequest(37.1, -122.1, 37.2, -122.2, "Home", "Work")
        val result = intent.performCalculation(req)
        assertFalse(result.available); assertEquals("", result.fareEstimate); assertEquals(-1, result.etaMinutes)
    }

    @Test fun wideLayoutRendersBothSlots() {
        compose.setContent {
            TaxiRideWideLayout(map = { Box(Modifier.testTag("mapslot")) { Text("MAPSLOT") } }, panel = { Box(Modifier.testTag("panelslot")) { Text("PANELSLOT") } })
        }
        compose.onNodeWithText("MAPSLOT").assertIsDisplayed(); compose.onNodeWithText("PANELSLOT").assertIsDisplayed()
        compose.onNodeWithTag("mapslot").assertExists()
    }

    @Test fun mainActivityLaunchAndNewIntent() {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(RideHandoffContract.bookingDeepLink(37.1, -122.1, "Home", 37.2, -122.2, "Work"))
        }
        val c = Robolectric.buildActivity(MainActivity::class.java, intent); c.create().start().resume()
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertNotNull(c.get())
        val second = Intent(ctx, MainActivity::class.java).apply {
            putExtra(com.vayunmathur.taxi.notifications.RideLiveUpdate.EXTRA_TRACK_RIDE_ID, "ride-9")
        }
        c.newIntent(second); Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("ride-9", c.get().intent.getStringExtra(com.vayunmathur.taxi.notifications.RideLiveUpdate.EXTRA_TRACK_RIDE_ID))
        val blank = Intent(ctx, MainActivity::class.java).apply {
            putExtra(com.vayunmathur.taxi.notifications.RideLiveUpdate.EXTRA_TRACK_RIDE_ID, " ")
        }
        c.newIntent(blank); c.pause().stop().destroy()
    }
}
