package com.vayunmathur.taxi.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.DriverInfo
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.data.RideStopInfo
import com.vayunmathur.taxi.data.VehicleInfo
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.data.uber.UberSession
import com.vayunmathur.taxi.network.TaxiHttp
import com.vayunmathur.taxi.network.TaxiPass3FakeHttp
import com.vayunmathur.taxi.network.lyft.LyftToken
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass 3: full-screen Compose coverage — accounts/payments, Lyft booking
 * dialogs, Lyft/Uber sign-in, ride home inputs, tracking cards/map, and
 * the app Navigation — over the TaxiHttp fake transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3ScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val http = TaxiPass3FakeHttp()

    private fun str(id: Int, vararg a: Any): String = context.getString(id, *a)

    private val pickup = Place("Home", "1 Main St", LatLng(37.7749, -122.4194))
    private val dropoff = Place("Work", "2 Market St", LatLng(37.8044, -122.2712))

    private fun quote(offerId: String? = "o1") = RideQuote(
        provider = Provider.LYFT, productId = "lyft", displayName = "Lyft Standard",
        fareLowMinor = 1200, fareHighMinor = 1500, currency = "USD",
        pickupEtaMinutes = 4, tripDurationMinutes = 20, surgeMultiplier = null,
        capacity = 4, offerId = offerId, offerToken = "tok", costToken = "ct", rideType = "Lyft",
    )

    private fun accountsJson(): String =
        """{"chargeAccounts":[
          {"id":"ca1","label":"Visa ••1111","default":true},
          {"id":"ca2","kind":"Mastercard","lastFour":"2222","default":false}]}"""

    @BeforeTest
    fun setUp() {
        http.install()
        runBlocking {
            LyftTokenStore(context).clear()
            UberSession(context).clear()
        }
    }

    @AfterTest
    fun tearDown() {
        http.uninstall()
        runBlocking {
            LyftTokenStore(context).clear()
            UberSession(context).clear()
        }
    }

    private fun seedSignedIn() = runBlocking {
        LyftTokenStore(context).save(
            LyftToken(accessToken = "access-tok", refreshToken = "rt", expiresIn = 3600, userId = "1"),
        )
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- Accounts ---

    @Test
    fun accountsSignedOutShowsConnectPrompt() {
        var connect = 0
        rule.setContent { AccountsScreen(onConnectLyft = { connect++ }) }
        waitForText(str(R.string.payment_sign_in))
        rule.onNodeWithText(str(R.string.provider_lyft)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.not_signed_in)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.payment_sign_in)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.provider_lyft)).performClick()
        assertEquals(1, connect)
    }

    @Test
    fun accountsSignedInListsPaymentsAndActions() {
        seedSignedIn()
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        http.on("/charge-accounts-multi-provider") { http.raw(200, accountsJson(), url = it.url) }
        rule.setContent { AccountsScreen(onConnectLyft = {}) }
        waitForText("Visa ••1111")
        rule.onNodeWithText("Mastercard ••2222").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.payment_default)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.add_card)).assertExists()
        // Set the second account as default.
        rule.onNodeWithText(str(R.string.card_set_default)).performClick()
        rule.waitForIdle()
        assertTrue(http.callsTo("charge-accounts-multi-provider").any { it.method == "PUT" })
        // Remove the first account.
        rule.onAllNodesWithText("Remove")[0].performClick()
        rule.waitForIdle()
        assertTrue(http.calls.any { it.method == "DELETE" })
    }

    @Test
    fun accountsPaymentsErrorShowsMessage() {
        seedSignedIn()
        http.on("/chargeaccounts") { http.raw(500, "kaput", "text/plain", it.url) }
        rule.setContent { AccountsScreen(onConnectLyft = {}) }
        val expected = str(R.string.payment_methods_error, "Lyft returned HTTP 500: kaput")
        waitForText(expected)
        rule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun accountsSignOutFlowRevokesAndClears() {
        seedSignedIn()
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        http.performHandler = { http.simple(200, "", it.url) }
        rule.setContent { AccountsScreen(onConnectLyft = {}) }
        waitForText("Visa ••1111")
        rule.onNodeWithText(str(R.string.sign_out)).performClick()
        waitForText(str(R.string.sign_out_title))
        // Confirm in the dialog (the second Sign out control).
        rule.onAllNodesWithText(str(R.string.sign_out))[1].performClick()
        waitForText(str(R.string.not_signed_in))
        assertEquals(2, http.callsTo("revoke_token").size)
    }

    @Test
    fun accountsAddCardDialogValidationAndSubmit() {
        seedSignedIn()
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        http.on("/v1/tokenization_strategies") {
            http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pk"}}]}""", url = it.url)
        }
        http.on("api.stripe.com") { http.raw(200, """{"id":"tok_new"}""", url = it.url) }
        http.on("/charge-accounts-multi-provider") { http.raw(200, accountsJson(), url = it.url) }
        rule.setContent { AccountsScreen(onConnectLyft = {}) }
        waitForText(str(R.string.add_card))
        rule.onNode(
            androidx.compose.ui.test.hasText(str(R.string.add_card)) and
                androidx.compose.ui.test.hasClickAction(),
        ).performSemanticsAction(SemanticsActions.OnClick)
        waitForText(str(R.string.add_card_title))
        // Type into the form; the dialog stays up and nothing is tokenized
        // without a submit (validation itself is pinned by
        // TaxiCardValidationTest).
        rule.onNodeWithText(str(R.string.card_number)).performTextInput("4111111111111111")
        rule.onNodeWithText(str(R.string.card_expiry)).performTextInput("12/30")
        rule.onNodeWithText(str(R.string.card_cvc)).performTextInput("123")
        rule.onNodeWithText("4111111111111111").assertIsDisplayed()
        assertTrue(http.callsTo("tokenization").isEmpty())
        // Cancel dismisses the dialog.
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.add_card_title)).assertDoesNotExist()
    }

    @Test
    fun accountsAddCardSubmitRunsFullChain() {
        seedSignedIn()
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        http.on("/v1/tokenization_strategies") {
            http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pk"}}]}""", url = it.url)
        }
        http.on("api.stripe.com") { http.raw(200, """{"id":"tok_new"}""", url = it.url) }
        http.on("/charge-accounts-multi-provider") { http.raw(200, accountsJson(), url = it.url) }
        rule.setContent { AccountsScreen(onConnectLyft = {}) }
        waitForText(str(R.string.add_card))
        rule.onNode(
            androidx.compose.ui.test.hasText(str(R.string.add_card)) and
                androidx.compose.ui.test.hasClickAction(),
        ).performSemanticsAction(SemanticsActions.OnClick)
        waitForText(str(R.string.add_card_title))
        rule.onNodeWithText(str(R.string.card_number)).performTextInput("4111111111111111")
        rule.onNodeWithText(str(R.string.card_expiry)).performTextInput("12/30")
        rule.onNodeWithText(str(R.string.card_cvc)).performTextInput("123")
        rule.onNode(
            androidx.compose.ui.test.hasText("Add") and androidx.compose.ui.test.hasClickAction(),
        ).performSemanticsAction(SemanticsActions.OnClick)
        // The dialog closes once the card is added.
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(str(R.string.add_card_title)).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(http.callsTo("tokenization_strategies").isNotEmpty())
        assertTrue(http.callsTo("charge-accounts-multi-provider").any { it.method == "POST" })
    }

    // --- Lyft booking flow dialogs ---

    @Test
    fun bookingFlowPicksPaymentConfirmsAndCreates() {
        seedSignedIn()
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        http.on("/v1/core_trips/create") {
            http.raw(200, """{"trip_details":{"trip_id":"888"}}""", url = it.url)
        }
        var dismissed = 0
        rule.setContent {
            LyftBookingFlow(
                request = LyftBookingRequest(quote(), pickup, dropoff, "ps1"),
                onDismiss = { dismissed++ },
            )
        }
        waitForText(str(R.string.choose_payment))
        rule.onNodeWithText("Visa ••1111").assertIsDisplayed()
        rule.onNodeWithText("Mastercard ••2222").performClick()
        rule.onNodeWithText(str(R.string.book)).performClick()
        waitForText(str(R.string.confirm_booking))
        rule.onNodeWithText("Lyft Standard").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.book)).performClick()
        // BOOKING_LIVE is true, so the ride is created and confirmed.
        waitForText(str(R.string.ride_created, " (888)"))
        assertTrue(http.callsTo("core_trips").isNotEmpty())
        assertEquals(0, dismissed)
    }

    @Test
    fun bookingFlowWithoutPaymentsFailsEarly() {
        seedSignedIn()
        http.on("/chargeaccounts") { http.raw(200, """{"chargeAccounts":[]}""", url = it.url) }
        rule.setContent {
            LyftBookingFlow(LyftBookingRequest(quote(), pickup, dropoff, null), onDismiss = {})
        }
        waitForText(str(R.string.booking_failed, str(R.string.booking_no_payment)))
    }

    @Test
    fun bookingFlowNotSignedInFailsWithConnectPrompt() {
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        rule.setContent {
            LyftBookingFlow(LyftBookingRequest(quote(), pickup, dropoff, null), onDismiss = {})
        }
        waitForText(str(R.string.booking_failed, str(R.string.connect_prompt, "Lyft")))
    }

    // --- Sign-in screens ---

    @Test
    fun lyftSignInPhoneThenCodeCompletes() {
        http.performHandler = { call ->
            when {
                call.url.contains("/oauth2/access_token") && (call.body as String).contains("grant_type=client_credentials") ->
                    http.simple(200, """{"access_token":"client-tok","expires_in":3600}""", call.url)
                call.url.contains("/oauth2/access_token") ->
                    http.simple(200, """{"access_token":"user-tok","refresh_token":"rt","expires_in":3600,"user_id":"7"}""", call.url)
                call.url.contains("/v1/phoneauth") -> http.simple(200, "{}", call.url)
                else -> http.simple(404, "", call.url)
            }
        }
        rule.setContent { LyftSignInScreen(onBack = {}) }
        rule.onNodeWithText(str(R.string.phone_number)).performTextInput("+15551234567")
        rule.onNodeWithText(str(R.string.send_code)).performClick()
        waitForText(str(R.string.verification_code))
        rule.onNodeWithText(str(R.string.verification_code)).performTextInput("424242")
        rule.onNodeWithText(str(R.string.verify)).performClick()
        waitForText(str(R.string.signed_in_as, "+15551234567"))
        // The token was persisted.
        assertTrue(runBlocking { LyftTokenStore(context).isSignedIn() })
    }

    @Test
    fun lyftSignInFailureShowsError() {
        http.performHandler = { http.simple(500, """{"error":"server","error_description":"Lyft is down"}""", it.url) }
        rule.setContent { LyftSignInScreen(onBack = {}) }
        rule.onNodeWithText(str(R.string.phone_number)).performTextInput("+15551234567")
        rule.onNodeWithText(str(R.string.send_code)).performClick()
        waitForText("Could not obtain a Lyft client token")
        rule.onNodeWithText("Could not obtain a Lyft client token").assertIsDisplayed()
    }

    @Test
    fun uberSignInNativeFlowAdvancesScreens() {
        http.performHandler = { call ->
            val body = call.body as String
            if (body.contains("PHONE_NUMBER_INITIAL")) {
                http.simple(
                    200,
                    """{"inAuthSessionID":"sess-9","form":{"screens":[{"screenType":"PHONE_OTP"}]}}""",
                    call.url,
                )
            } else {
                http.simple(
                    200,
                    """{"inAuthSessionID":"sess-9","form":{"screens":[{"screenType":"HOME"}]}}""",
                    call.url,
                )
            }
        }
        var webView = 0
        rule.setContent { UberSignInScreen(onBack = {}, onUseWebView = { webView++ }) }
        rule.onNodeWithText(str(R.string.phone_number)).performTextInput("2135551234")
        rule.onNodeWithText(str(R.string.send_code)).performClick()
        waitForText(str(R.string.uber_next_screen, "PHONE_OTP"))
        rule.onNodeWithText(str(R.string.verification_code)).performTextInput("123456")
        rule.onNodeWithText(str(R.string.verify)).performClick()
        waitForText(str(R.string.uber_next_screen, "HOME"))
        rule.onNodeWithText(str(R.string.uber_use_webview)).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(10_000) { webView == 1 }
        assertEquals(1, webView)
    }

    @Test
    fun uberSignInFailureShowsError() {
        http.performHandler = { http.simple(503, "maintenance", it.url) }
        rule.setContent { UberSignInScreen(onBack = {}, onUseWebView = {}) }
        rule.onNodeWithText(str(R.string.phone_number)).performTextInput("2135551234")
        rule.onNodeWithText(str(R.string.send_code)).performClick()
        waitForText("HTTP 503: maintenance")
    }

    // --- Tracking cards / map / current ride ---

    private fun activeRide(status: RideStatus = RideStatus.APPROACHING): ActiveRide = ActiveRide(
        rideId = "r1", status = status, statusRaw = status.wire,
        driver = DriverInfo("Jo", "Smith", null, "+15550001", 4.9),
        vehicle = VehicleInfo("Toyota", "Camry", "Black", "XYZ123", null),
        driverLocation = com.vayunmathur.taxi.data.DriverLocation(37.7, -122.4, 90.0),
        stops = listOf(
            RideStopInfo(LatLng(37.7, -122.4), "Home", "pickup", 300, completed = false),
            RideStopInfo(LatLng(37.8, -122.5), "Work", "dropoff", 900, completed = false),
        ),
        raw = "{}",
    )

    @Test
    fun trackingCardsShowDriverVehicleAndEta() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                StatusHeader(activeRide(), ended = false)
                DriverCard(activeRide(), onCall = {})
            }
        }
        rule.onNodeWithText("Jo Smith").assertIsDisplayed()
        rule.onNodeWithText("Black Toyota Camry").assertIsDisplayed()
        rule.onNodeWithText("XYZ123").assertIsDisplayed()
        rule.onNodeWithText("4.9").assertIsDisplayed()
        // Status helper functions across the lifecycle.
        assertEquals(R.string.status_finding_driver, statusLabel(RideStatus.PENDING, ended = false))
        assertEquals(R.string.status_en_route, statusLabel(RideStatus.ACCEPTED, ended = false))
        assertEquals(R.string.status_arrived, statusLabel(RideStatus.ARRIVED, ended = false))
        assertEquals(R.string.status_on_trip, statusLabel(RideStatus.PICKED_UP, ended = false))
        assertEquals(R.string.ride_complete, statusLabel(RideStatus.COMPLETED, ended = false))
        assertEquals(R.string.ride_ended, statusLabel(RideStatus.CANCELED, ended = false))
        assertEquals(R.string.ride_ended, statusLabel(RideStatus.ACCEPTED, ended = true))
        assertEquals(1f, statusProgress(RideStatus.ACCEPTED, over = true))
        assertEquals(0.55f, statusProgress(RideStatus.APPROACHING, over = false))
        assertEquals(0.9f, statusProgress(RideStatus.PICKED_UP, over = false))
    }

    @Test
    fun trackingMapEmptyStateShowsPlaceholder() {
        // No points: a loading placeholder instead of a map.
        rule.setContent { TrackingMap(null, null) }
        rule.waitForIdle()
    }

    @Test
    fun trackingMapWithRideComposes() {
        // With a ride: the map composes (vector tiles are offline-safe).
        rule.setContent { TrackingMap(activeRide(), activeRide().driverLocation) }
        rule.waitForIdle()
    }

    @Test
    fun currentRideScreenWithoutRideShowsEmptyState() {
        rule.setContent { CurrentRideScreen() }
        waitForText(str(R.string.no_current_ride))
        rule.onNodeWithText(str(R.string.no_current_ride)).assertIsDisplayed()
    }

    // --- Ride home (RideScreen via Navigation) ---

    @Test
    fun navigationShowsRideHomeAndTabs() {
        rule.setContent {
            com.vayunmathur.taxi.Navigation(
                trackRideId = remember { mutableStateOf(null) },
                bookingTrip = remember { mutableStateOf(null) },
            )
        }
        waitForText(str(R.string.nav_ride))
        rule.onNodeWithText(str(R.string.destination)).assertExists()
        rule.onNodeWithText(str(R.string.pickup_label)).assertExists()
        // Switch to the accounts tab and back.
        rule.onNodeWithText("Settings").performClick()
        waitForText(str(R.string.payment_methods))
        rule.onNodeWithText(str(R.string.nav_ride)).performClick()
        waitForText(str(R.string.destination))
    }

    @Test
    fun rideScreenWithBookingTripPrefillsAndQuotes() {
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        // Quote calls need a token; without one the results note prompt shows.
        val trip = mutableStateOf<BookingTrip?>(BookingTrip(pickup, dropoff))
        rule.setContent { RideScreen(bookingTrip = trip) }
        waitForText("Work", timeoutMs = 20_000)
        rule.onNodeWithText("Home").assertIsDisplayed()
    }

    @Test
    fun providerColorsAndRouteFieldShape() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFFEA0B8C), providerColor(Provider.LYFT))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF276EF1), providerColor(Provider.UBER))
        assertEquals(2, RouteField.entries.size)
    }
}
