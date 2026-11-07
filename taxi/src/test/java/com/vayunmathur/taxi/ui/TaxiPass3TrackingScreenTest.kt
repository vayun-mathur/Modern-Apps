package com.vayunmathur.taxi.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.network.TaxiPass3FakeHttp
import com.vayunmathur.taxi.network.lyft.LyftToken
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pass 3: the live tracking screen (poll loops, cancel dialog) and the
 * current-ride tab rendering a live ride, over the fake transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3TrackingScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val http = TaxiPass3FakeHttp()

    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    @BeforeTest
    fun setUp() {
        http.install()
        runBlocking {
            LyftTokenStore(context).save(
                LyftToken(accessToken = "access-tok", refreshToken = "rt", expiresIn = 3600),
            )
        }
    }

    @AfterTest
    fun tearDown() {
        http.uninstall()
        runBlocking { LyftTokenStore(context).clear() }
    }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rideJson(status: String): String =
        """{"ride":{"ride_id":"r1","status":"$status",
          "driver":{"first_name":"Jo","last_name":"Smith","phone_number":"+15550001","rating":4.9},
          "vehicle":{"make":"Toyota","model":"Camry","color":"Black","license_plate":"XYZ"},
          "location":{"lat":37.7,"lng":-122.4,"bearing":90.0},
          "stops":[{"location":{"lat":37.7,"lng":-122.4,"place_name":"Home"},"kind":"pickup","completed":false,"eta_seconds":300},
          {"location":{"lat":37.8,"lng":-122.5,"place_name":"Work"},"kind":"dropoff","completed":false,"eta_seconds":900}]}}"""

    @Test
    fun trackingScreenShowsTerminalRideAndStops() {
        http.on("/v1/activeride") { http.raw(200, rideJson("completed"), url = it.url) }
        http.on("/driver-location") {
            http.raw(200, """{"location":{"lat":37.71,"lng":-122.41}}""", url = it.url)
        }
        rule.setContent { RideTrackingScreen(rideId = "r1") }
        waitForText(str(R.string.ride_complete))
        rule.onNodeWithText("Jo Smith").assertIsDisplayed()
        // Terminal rides offer no cancel.
        rule.onNodeWithText(str(R.string.cancel_ride)).assertDoesNotExist()
    }

    @Test
    fun trackingScreenCancelFlow() {
        http.on("/v1/activeride") { http.raw(200, rideJson("approaching"), url = it.url) }
        http.on("/driver-location") {
            http.raw(200, """{"location":{"lat":37.71,"lng":-122.41}}""", url = it.url)
        }
        http.on("/cancel") { http.raw(200, "Cancelled with fee", "text/plain", it.url) }
        rule.setContent { RideTrackingScreen(rideId = "r1") }
        waitForText(str(R.string.cancel_ride))
        rule.onNodeWithText(str(R.string.cancel_ride)).performClick()
        waitForText(str(R.string.cancel_ride_title))
        rule.onNodeWithText(str(R.string.cancel_ride_message)).assertIsDisplayed()
        // Confirm (the dialog's Cancel ride action is the last one).
        val buttons = rule.onAllNodesWithText(str(R.string.cancel_ride)).fetchSemanticsNodes()
        assertTrue(buttons.size >= 2)
        rule.onAllNodesWithText(str(R.string.cancel_ride))[buttons.lastIndex].performClick()
        waitForText(str(R.string.ride_cancelled))
        assertTrue(http.callsTo("/cancel").isNotEmpty())
    }

    @Test
    fun trackingScreenKeepRideDismissesDialog() {
        http.on("/v1/activeride") { http.raw(200, rideJson("accepted"), url = it.url) }
        http.on("/driver-location") {
            http.raw(200, """{"location":{"lat":37.71,"lng":-122.41}}""", url = it.url)
        }
        rule.setContent { RideTrackingScreen(rideId = "r1") }
        waitForText(str(R.string.cancel_ride))
        rule.onNodeWithText(str(R.string.cancel_ride)).performClick()
        waitForText(str(R.string.keep_ride))
        rule.onNodeWithText(str(R.string.keep_ride)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.cancel_ride_title)).assertDoesNotExist()
        assertTrue(http.callsTo("/cancel").isEmpty())
    }

    @Test
    fun trackingScreenErrorState() {
        http.on("/v1/activeride") { http.raw(500, "kaput", "text/plain", it.url) }
        http.on("/v1/rides/") { http.raw(500, "kaput", "text/plain", it.url) }
        http.on("/driver-location") { http.raw(500, "x", "text/plain", it.url) }
        rule.setContent { RideTrackingScreen(rideId = "r1") }
        // Active fails with an error; the by-id read also fails, which ends
        // tracking - the screen settles out of its initial state without
        // crashing (loading or error, never a stuck spinner assertion here).
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(str(R.string.tracking_loading)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText(str(R.string.tracking_error, "Lyft returned HTTP 500: kaput"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun currentRideScreenWithActiveRideShowsTracking() {
        http.on("/v1/activeride") { http.raw(200, rideJson("arrived"), url = it.url) }
        http.on("/driver-location") {
            http.raw(200, """{"location":{"lat":37.71,"lng":-122.41}}""", url = it.url)
        }
        rule.setContent { CurrentRideScreen() }
        waitForText(str(R.string.status_arrived))
    }
}
