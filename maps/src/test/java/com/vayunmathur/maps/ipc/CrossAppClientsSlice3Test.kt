package com.vayunmathur.maps.ipc

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.MainActivity
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z): the cross-app clients
 * ([OrderLookupClient], [RideEstimateClient], [RideHandoffContract]).
 *
 * fooddelivery/taxi are not installed under test, so the "absent app"
 * degradation (null estimate, no crash) is exercised for real: launching a
 * [MainActivity] initialises the shared [IntentLauncher], whose package
 * check reports the missing package and the clients map it to null. The
 * blank-name fast path and the `taxi://book` deep-link shape need no
 * activity at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossAppClientsSlice3Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun initWork() {
        runCatching {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                    .build(),
            )
        }
    }

    private fun launchActivity(): MainActivity {
        val intent = Intent(context, MainActivity::class.java)
        return Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
    }

    private fun restaurant() = SpecificFeature.Restaurant(
        name = "Pizza Place",
        phone = null,
        website = null,
        menu = null,
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )

    @Test
    fun `taxi app is reported absent`() {
        assertFalse(RideEstimateClient.isInstalled(context))
    }

    @Test
    fun `booking deep link carries both endpoints`() {
        val link = RideHandoffContract.bookingDeepLink(
            pickupLat = 37.8, pickupLng = -122.4, pickupLabel = "Home",
            destLat = 37.7, destLng = -122.5, destLabel = "Work",
        )
        assertTrue(link.startsWith("taxi://book?"), link)
        assertTrue("pickup_lat=37.8" in link, link)
        assertTrue("dest_label=Work" in link, link)
        assertTrue("pickup_label=Home" in link, link)
    }

    @Test
    fun `booking deep link omits blank labels`() {
        val link = RideHandoffContract.bookingDeepLink(
            pickupLat = 1.0, pickupLng = 2.0, pickupLabel = "  ",
            destLat = 3.0, destLng = 4.0, destLabel = null,
        )
        assertFalse("pickup_label" in link, link)
        assertFalse("dest_label" in link, link)
        assertTrue("dest_lat=3.0" in link, link)
    }

    @Test
    fun `order lookup rejects blank names without launching`() = runBlocking {
        assertNull(OrderLookupClient.lookup(context, "   ", 0.0, 0.0))
    }

    @Test
    fun `order lookup degrades to null when fooddelivery is absent`() = runBlocking {
        val activity = launchActivity()
        try {
            assertNull(OrderLookupClient.lookup(context, "Pizza Place", 37.8, -122.4))
        } finally {
            activity.finish()
        }
    }

    @Test
    fun `ride estimate degrades to null when taxi is absent`() = runBlocking {
        val activity = launchActivity()
        try {
            assertNull(
                RideEstimateClient.estimate(context, 37.8, -122.4, 37.7, -122.5),
            )
        } finally {
            activity.finish()
        }
    }

    @Test
    fun `missing app exception names the package`() {
        val e = MissingAppException("com.example.app")
        assertEquals("com.example.app", e.packageName)
        assertTrue(e.message!!.contains("com.example.app"))
    }

    @Test
    fun `order info carries availability and link`() {
        assertEquals(OrderInfo(false, null), OrderInfo(false, null))
        assertEquals("taxi://book?x=1", OrderInfo(true, "taxi://book?x=1").deepLinkUri)
    }

    @Test
    fun `ride estimate carries fare and eta`() {
        val est = RideEstimate(available = true, fareEstimate = "$12", etaMinutes = 5)
        assertTrue(est.available)
        assertEquals("$12", est.fareEstimate)
        assertEquals(5, est.etaMinutes)
        assertEquals(RideEstimate(false, null, null), RideEstimate(false, null, null))
    }

    @Test
    fun `restaurant feature exposes its position for handoff`() {
        val feature = restaurant()
        assertEquals(37.8, feature.position.latitude)
        assertEquals("Pizza Place", feature.name)
    }
}
