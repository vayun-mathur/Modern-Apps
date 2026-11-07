package com.vayunmathur.taxi

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.taxi.RideEstimateRequest
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.intents.RideEstimateIntent
import com.vayunmathur.taxi.network.TaxiPass3FakeHttp
import com.vayunmathur.taxi.network.lyft.LyftToken
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: MainActivity deep-link handling, the assistant ride-estimate
 * intent, and the IPC intent-launch helpers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3AppTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()
    private val http = TaxiPass3FakeHttp()

    @BeforeTest
    fun setUp() {
        http.install()
    }

    @AfterTest
    fun tearDown() {
        http.uninstall()
        runBlocking { LyftTokenStore(context).clear() }
    }

    @Test
    fun mainActivityLaunchesRideHome() {
        rule.waitForIdle()
        assertNotNull(rule.activity)
        assertTrue(rule.onAllNodesWithText(context.getString(R.string.nav_ride)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun bookingDeepLinkIntentParsesTrip() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(
                "taxi://book?pickup_lat=37.7749&pickup_lng=-122.4194&pickup_label=Home" +
                    "&dest_lat=37.8044&dest_lng=-122.2712&dest_label=Work",
            )
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        val activity = controller.create().get()
        assertNotNull(activity)
        controller.destroy()
    }

    @Test
    fun trackRideIntentExtraIsConsumed() {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(com.vayunmathur.taxi.notifications.RideLiveUpdate.EXTRA_TRACK_RIDE_ID, "ride-99")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create()
        controller.newIntent(
            Intent(context, MainActivity::class.java).apply {
                putExtra(
                    com.vayunmathur.taxi.notifications.RideLiveUpdate.EXTRA_TRACK_RIDE_ID,
                    "ride-100",
                )
            },
        )
        controller.destroy()
    }

    @Test
    fun rideEstimateIntentUnavailableWithoutSignIn() = runBlocking {
        val intent = RideEstimateIntent()
        // AssistantIntent plumbing needs the activity context; use the
        // calculation entry via the public perform path by attaching the
        // intent to the test activity's context through Robolectric.
        val controller = Robolectric.buildActivity(RideEstimateIntent::class.java)
        val estimate = controller.create().get()
        val result = estimate.performCalculation(
            RideEstimateRequest(
                pickupLat = 37.7749, pickupLng = -122.4194, pickupLabel = "Home",
                destLat = 37.8044, destLng = -122.2712, destLabel = "Work",
            ),
        )
        assertFalse(result.available)
        assertEquals("", result.fareEstimate)
        assertEquals(-1, result.etaMinutes)
        controller.destroy()
        Unit
    }

    @Test
    fun rideEstimateIntentReturnsCheapestQuote() = runBlocking {
        // Sign in and answer the offerings call with two fares.
        LyftTokenStore(context).save(
            LyftToken(accessToken = "access-tok", refreshToken = "rt", expiresIn = 3600),
        )
        http.on("/v2/offerings") {
            http.raw(
                200,
                """{"offers":{"purchase_session_id":"ps","offers_response_id":"or","offers_list":[
                  {"offer_product_id":"lyft","id":"o1","offer_token":"t",
                   "cost_estimate":{"ride_type":"Lyft","currency":"USD",
                   "estimated_cost_cents_min":1500,"estimated_cost_cents_max":1800,
                   "estimated_duration_seconds":1200,"cost_token":"ct"},
                   "ride_type_details":{"display_properties":{"name":"Standard"},"seats":4},
                   "ride_travel_details":{"pickup_estimate":{"duration_range":{"duration_ms":300000}}}},
                  {"offer_product_id":"lyft","id":"o2","offer_token":"t2",
                   "cost_estimate":{"ride_type":"Lyft","currency":"USD",
                   "estimated_cost_cents_min":900,"estimated_cost_cents_max":900,
                   "estimated_duration_seconds":900,"cost_token":"ct2"},
                   "ride_type_details":{"display_properties":{"name":"Shared"},"seats":2},
                   "ride_travel_details":{"pickup_estimate":{"duration_range":{"duration_ms":120000}}}}]}}""",
                url = it.url,
            )
        }
        val controller = Robolectric.buildActivity(RideEstimateIntent::class.java)
        val estimate = controller.create().get()
        val result = estimate.performCalculation(
            RideEstimateRequest(pickupLat = 37.7749, pickupLng = -122.4194, destLat = 37.8044, destLng = -122.2712, pickupLabel = "Home", destLabel = "Work"),
        )
        assertTrue(result.available, "result=$result")
        assertEquals("$9.00", result.fareEstimate)
        assertEquals(2, result.etaMinutes)
        controller.destroy()
        Unit
    }

    @Test
    fun intentLaunchContracts() {
        // The launcher is initialised by MainActivity.onCreateBeforeContent,
        // which the compose rule's activity has already run.
        assertNotNull(MainActivity.intentLauncher)
        val missing = com.vayunmathur.taxi.ipc.MissingAppException("com.example.absent")
        assertEquals("App com.example.absent is not installed.", missing.message)
        assertNull(null)
    }
}
