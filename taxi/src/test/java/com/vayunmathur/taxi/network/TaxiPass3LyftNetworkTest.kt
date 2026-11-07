package com.vayunmathur.taxi.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.network.lyft.LyftApiSession
import com.vayunmathur.taxi.network.lyft.LyftAuth
import com.vayunmathur.taxi.network.lyft.LyftAuthResult
import com.vayunmathur.taxi.network.lyft.LyftProvider
import com.vayunmathur.taxi.network.lyft.LyftToken
import com.vayunmathur.taxi.provider.QuoteRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
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
 * Pass 3: the Lyft network stack (auth, quotes, rides, payments, card
 * tokenization, exploratory endpoints, provider facade, quote repository)
 * driven through the TaxiHttp fake transport. No real network is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3LyftNetworkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val http = TaxiPass3FakeHttp()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val pickup = Place("Home", "1 Main St", LatLng(37.7749, -122.4194))
    private val dropoff = Place("Work", "2 Market St", LatLng(37.8044, -122.2712))

    @BeforeTest
    fun setUp() {
        http.install()
    }

    @AfterTest
    fun tearDown() = runTest {
        http.uninstall()
        LyftTokenStore(context).clear()
    }

    private suspend fun seedToken(
        access: String = "access-tok",
        refresh: String? = "refresh-tok",
        expiresIn: Long = 3600,
    ) {
        LyftTokenStore(context).save(
            LyftToken(accessToken = access, refreshToken = refresh, expiresIn = expiresIn, userId = "4242"),
        )
    }

    private fun offerJson(): String =
        """{"offers":{"purchase_session_id":"ps1","offers_response_id":"or1","offers_list":[
          {"offer_product_id":"lyft","id":"o1","offer_token":"tok",
           "cost_estimate":{"ride_type":"Lyft","currency":"USD","estimated_cost_cents_min":500,
           "estimated_cost_cents_max":900,"estimated_duration_seconds":1200,
           "primetime_multiplier":1.0,"cost_token":"ct","cost_token_expiry_time":9999999999},
           "ride_type_details":{"display_properties":{"name":"Lyft Standard"},"seats":4},
           "ride_travel_details":{"pickup_estimate":{"duration_range":{"duration_ms":240000}}}}]}}"""

    private fun rideJson(status: String = "accepted"): String =
        """{"ride":{"ride_id":"r1","status":"$status",
          "driver":{"first_name":"Jo","last_name":"Smith","image_url":"u","phone_number":"+1","rating":4.9},
          "vehicle":{"make":"Toyota","model":"Camry","color":"Black","license_plate":"XYZ","image_url":"v"},
          "location":{"lat":37.7,"lng":-122.4,"bearing":90.0},
          "stops":[{"location":{"lat":37.7,"lng":-122.4,"place_name":"Home"},"kind":"pickup","completed":false,"eta_seconds":120},
          {"location":{"lat":37.8,"lng":-122.5,"place_name":"Work"},"kind":"dropoff","completed":false,"eta_seconds":600}]}}"""

    private fun accountsJson(): String =
        """{"chargeAccounts":[
          {"id":"ca1","label":"Visa ••1111","default":true},
          {"id":"ca2","kind":"Mastercard","lastFour":"2222","default":false}]}"""

    // --- LyftAuth ---

    @Test
    fun authRequestSmsCodeUsesClientTokenFirst() = runTest {
        http.performHandler = { call ->
            when {
                call.url.contains("/oauth2/access_token") ->
                    http.simple(200, """{"access_token":"client-tok","token_type":"Bearer","expires_in":3600}""", call.url)
                call.url.contains("/v1/phoneauth") -> http.simple(200, "{}", call.url)
                else -> http.simple(404, "unexpected ${call.url}", call.url)
            }
        }
        val result = LyftAuth.requestSmsCode("+15551234567")
        assertTrue(result is LyftAuthResult.Success)
        // The client token may already be cached from another test in this
        // class; when the call does happen it must be a client_credentials grant.
        http.callsTo("/oauth2/access_token").firstOrNull()?.let { tokenCall ->
            assertTrue((tokenCall.body as String).contains("grant_type=client_credentials"))
            assertTrue(tokenCall.headers.values.any { it.toString().startsWith("Basic ") })
        }
        val phoneCall = http.callsTo("/v1/phoneauth").first()
        assertTrue((phoneCall.headers["Authorization"] ?: "").toString().startsWith("Bearer "))
        assertTrue((phoneCall.body as String).contains("+15551234567"))
        // Common headers are on the wire.
        assertTrue(phoneCall.headers.keys.any { it.equals("user-agent", true) })
    }

    @Test
    fun authVerifySmsCodeExchangesAndParsesToken() = runTest {
        http.performHandler = { call ->
            assertTrue(call.url.contains("/oauth2/access_token"))
            http.simple(
                200,
                """{"access_token":"user-tok","token_type":"Bearer","refresh_token":"rt-1","expires_in":7200,"user_id":"99"}""",
                call.url,
            )
        }
        val result = LyftAuth.verifySmsCode("+15551234567", "123456", "session-uuid-1")
        assertTrue(result is LyftAuthResult.Success)
        val token = (result as LyftAuthResult.Success).token
        assertEquals("user-tok", token.accessToken)
        assertEquals("rt-1", token.refreshToken)
        assertEquals(7200L, token.expiresIn)
        assertEquals("99", token.userId)
        val body = http.calls.first().body as String
        assertTrue(body.contains("grant_type=urn%3Alyft%3Aoauth2%3Agrant_type%3Aphone"), body)
        assertTrue(body.contains("phone_code=123456"))
        assertTrue(body.contains("login_session_uuid=session-uuid-1"))
        assertFalse(body.contains("email="))
    }

    @Test
    fun authVerifyWithEmailAnswersChallengeFields() = runTest {
        http.performHandler = { http.simple(200, """{"access_token":"t","expires_in":60}""", it.url) }
        assertTrue(LyftAuth.verifySmsCode("+1", "999", "s", email = "a@b.c") is LyftAuthResult.Success)
        val body = http.calls.first().body as String
        assertTrue(body.contains("email=a%40b.c"), body)
        assertTrue(body.contains("confirm=true"))
    }

    @Test
    fun authFailureShapesMapToResults() = runTest {
        // Web challenge.
        http.performHandler = {
            http.simple(400, """{"error":"browser_redirect_required","redirect_uri":"https://lyft.example/challenge"}""", it.url)
        }
        val web = LyftAuth.verifySmsCode("+1", "1", "s")
        assertEquals(LyftAuthResult.NeedsWebChallenge("https://lyft.example/challenge"), web)
        // Email-match challenge.
        http.performHandler = {
            http.simple(
                400,
                """{"error":"challenge_required","error_description":"Verify your email",
                   "challenges":[{"identifier":"email_match","data":"v******t@u*c.edu"}]}""",
                it.url,
            )
        }
        val challenge = LyftAuth.verifySmsCode("+1", "1", "s")
        assertTrue(challenge is LyftAuthResult.NeedsChallenge)
        assertEquals("email_match", (challenge as LyftAuthResult.NeedsChallenge).identifier)
        assertEquals("v******t@u*c.edu", challenge.hint)
        assertEquals("Verify your email", challenge.description)
        // Plain failure.
        http.performHandler = {
            http.simple(401, """{"error":"invalid_client","error_description":"bad client"}""", it.url)
        }
        assertEquals(
            LyftAuthResult.Failed("invalid_client", "bad client"),
            LyftAuth.verifySmsCode("+1", "1", "s"),
        )
        // Malformed error body falls back to the HTTP status.
        http.performHandler = { http.simple(500, "not json", it.url) }
        assertEquals(LyftAuthResult.Failed(null, "HTTP 500"), LyftAuth.verifySmsCode("+1", "1", "s"))
        // Success status with garbage body is an unreadable token response.
        http.performHandler = { http.simple(200, "garbage", it.url) }
        assertEquals(
            LyftAuthResult.Failed(null, "Unreadable token response"),
            LyftAuth.verifySmsCode("+1", "1", "s"),
        )
    }

    @Test
    fun authRefreshAndRevoke() = runTest {
        http.performHandler = { call ->
            if (call.url.contains("revoke_token")) {
                http.simple(200, "", call.url)
            } else {
                http.simple(200, """{"access_token":"fresh","refresh_token":"rt2","expires_in":3600}""", call.url)
            }
        }
        val refreshed = LyftAuth.refresh("rt-1")
        assertEquals("fresh", refreshed?.accessToken)
        assertEquals("rt2", refreshed?.refreshToken)
        assertTrue(LyftAuth.revoke("some-token"))
        val revoke = http.callsTo("revoke_token").first()
        assertTrue((revoke.body as String).contains("token=some-token"))
        assertTrue((revoke.headers["Authorization"] ?: "").toString().startsWith("Basic "))
        // Revoke failures are best-effort false.
        http.performHandler = { http.simple(500, "x", it.url) }
        assertFalse(LyftAuth.revoke("some-token"))
        assertNull(LyftAuth.refresh("rt-x").also { })
    }

    @Test
    fun authCommonHeadersAndSessionUuid() {
        val headers = LyftAuth.commonHeaders()
        assertTrue(headers["user-agent"]!!.startsWith("lyft:android:"))
        assertEquals("GEO_REGION_NA", headers["x-lyft-geo-region"])
        assertEquals("COMPLIANCE_REGION_CCPA", headers["x-lyft-payload-compliance"])
        assertTrue(headers.containsKey("x-timestamp-ms"))
        val uuid = LyftAuth.newSessionUuid()
        assertTrue(uuid.matches(Regex("[0-9a-f-]{36}")))
        assertTrue(LyftAuth.newSessionUuid() != uuid)
    }

    @Test
    fun tokenStoreRoundTripsAndExpiry() = runTest {
        val store = LyftTokenStore(context)
        assertFalse(store.isSignedIn())
        assertTrue(store.isExpired())
        store.save(LyftToken(accessToken = "a1", refreshToken = "r1", expiresIn = 3600, userId = "u7"))
        assertTrue(store.isSignedIn())
        assertFalse(store.isExpired())
        assertEquals("a1", store.accessToken())
        assertEquals("r1", store.refreshToken())
        assertEquals("u7", store.userId())
        // A token expiring within the skew window counts as expired.
        store.save(LyftToken(accessToken = "a2", expiresIn = 30))
        assertTrue(store.isExpired())
        // Blank tokens are not stored as present.
        store.save(LyftToken(accessToken = "a3", refreshToken = "", expiresIn = 3600, userId = ""))
        assertEquals("a3", store.accessToken())
        store.clear()
        assertFalse(store.isSignedIn())
        assertNull(store.accessToken())
    }

    // --- Quotes ---

    @Test
    fun quotesRequireSignInThenParseOffers() = runTest {
        val provider = LyftProvider(context)
        assertEquals(QuoteResult.NotSignedIn, provider.quotes(pickup, dropoff))
        seedToken()
        http.on("/v2/offerings") { http.raw(200, offerJson(), url = it.url) }
        val result = provider.quotes(pickup, dropoff)
        assertTrue(result is QuoteResult.Success)
        val success = result as QuoteResult.Success
        assertEquals("ps1", success.purchaseSessionId)
        assertEquals("or1", success.offersResponseId)
        assertEquals(1, success.quotes.size)
        assertEquals("Lyft Standard", success.quotes[0].displayName)
        assertEquals("o1", success.quotes[0].offerId)
        val call = http.callsTo("/v2/offerings").first()
        assertEquals("POST", call.method)
        assertTrue((call.headers["Authorization"] ?: "").toString().contains("Bearer access-tok"))
        assertTrue((call.body as String).contains("\"origin\""))
        // Update quotes hits the update endpoint with the session ids.
        val updated = provider.updateQuotes(pickup, dropoff, "or1", "ps1")
        assertTrue(updated is QuoteResult.Success)
        assertTrue(http.callsTo("/v2/offerings/update").isNotEmpty())
    }

    @Test
    fun quotesHttpErrorAndUnparseableBodyFail() = runTest {
        seedToken()
        val provider = LyftProvider(context)
        http.on("/v2/offerings") { http.raw(503, "upstream down", "text/plain", it.url) }
        val failed = provider.quotes(pickup, dropoff)
        assertTrue(failed is QuoteResult.Failed)
        assertTrue((failed as QuoteResult.Failed).message.contains("503"))
        http.uninstall(); http.install()
        http.on("/v2/offerings") { http.raw(200, """{"offers":{}}""", url = it.url) }
        val empty = provider.quotes(pickup, dropoff)
        assertTrue(empty is QuoteResult.Failed)
        assertTrue((empty as QuoteResult.Failed).message.contains("no offers"))
    }

    @Test
    fun quoteRepositoryQuotesAndRefreshFallsBack() = runTest {
        seedToken()
        http.on("/v2/offerings/update") { http.raw(500, "nope", "text/plain", it.url) }
        http.on("/v2/offerings") { http.raw(200, offerJson(), url = it.url) }
        val quotes = QuoteRepository.quotes(context, pickup, dropoff)
        assertTrue(quotes[Provider.LYFT] is QuoteResult.Success)
        // Refresh tries update first, then falls back to a fresh quote.
        http.calls.clear()
        val previous = mapOf<Provider, QuoteResult>(
            Provider.LYFT to QuoteResult.Success(emptyList(), "ps1", "or1"),
        )
        val refreshed = QuoteRepository.refresh(context, pickup, dropoff, previous)
        assertTrue(refreshed[Provider.LYFT] is QuoteResult.Success)
        assertEquals(1, http.callsTo("/v2/offerings/update").size)
        assertEquals(1, http.callsTo("/v2/offerings").filter { !it.url.contains("update") }.size)
    }

    // --- Rides ---

    @Test
    fun ridesLifecycleThroughFakeTransport() = runTest {
        seedToken()
        val provider = LyftProvider(context)
        http.on("/v1/activeride") { http.raw(200, rideJson(), url = it.url) }
        http.on("/driver-location") {
            http.raw(200, """{"location":{"lat":37.71,"lng":-122.41,"bearing":45.0}}""", url = it.url)
        }
        http.on("/v1/rides/r1/cancel") { http.raw(200, "Cancelled, no fee", "text/plain", it.url) }
        http.on("/v1/core_trips/create") {
            http.raw(200, """{"trip_details":{"trip_id":"777"}}""", url = it.url)
        }
        val active = provider.activeRide()
        assertTrue(active is RideStatusResult.Active)
        assertEquals("r1", (active as RideStatusResult.Active).ride.rideId)
        assertEquals(37.71, provider.driverLocation("r1")?.latitude)
        assertEquals(CancelResult.Done("Cancelled, no fee"), provider.cancelRide("r1"))

        val rideQuote = com.vayunmathur.taxi.data.RideQuote(
            provider = Provider.LYFT, productId = "lyft", displayName = "Lyft",
            fareLowMinor = 500, fareHighMinor = 900, currency = "USD",
            pickupEtaMinutes = 4, tripDurationMinutes = 20, surgeMultiplier = null,
            capacity = 4, offerId = "o1", offerToken = "tok", costToken = "ct", rideType = "Lyft",
        )
        // Dry run never sends.
        http.calls.clear()
        val dry = provider.createRide(rideQuote, pickup, dropoff, null, "ps1", dryRun = true)
        assertTrue(dry is BookingResult.DryRun)
        assertTrue((dry as BookingResult.DryRun).requestJson.contains("\"offer_id\":\"o1\""))
        assertTrue(http.callsTo("core_trips").isEmpty())
        // Live booking posts and parses the created id.
        val created = provider.createRide(rideQuote, pickup, dropoff, null, "ps1", dryRun = false)
        assertTrue(created is BookingResult.Created)
        assertEquals("777", (created as BookingResult.Created).rideId)
        // A quote without an offer id cannot book.
        val noOffer = provider.createRide(rideQuote.copy(offerId = null), pickup, dropoff, null, null, dryRun = false)
        assertTrue(noOffer is BookingResult.Failed)
    }

    @Test
    fun ridesNotFoundAndErrors() = runTest {
        seedToken()
        val provider = LyftProvider(context)
        http.on("/v1/activeride") { http.raw(404, "", url = it.url) }
        assertEquals(RideStatusResult.None, provider.activeRide())
        http.uninstall(); http.install()
        http.on("/v1/activeride") { http.raw(500, "boom", "text/plain", it.url) }
        assertTrue(provider.activeRide() is RideStatusResult.Failed)
        http.on("/v1/rides/") { http.raw(200, rideJson("completed"), url = it.url) }
        val byId = provider.rideById("r1")
        assertTrue(byId is RideStatusResult.Active)
        assertTrue(http.callsTo("/v1/rides/r1").isNotEmpty())
    }

    // --- Payments + tokenizer ---

    @Test
    fun paymentsListSetDefaultRemove() = runTest {
        seedToken()
        val provider = LyftProvider(context)
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        http.on("/charge-accounts-multi-provider") { http.raw(200, accountsJson(), url = it.url) }
        val methods = provider.paymentMethods()
        assertTrue(methods is PaymentMethodsResult.Success)
        val accounts = (methods as PaymentMethodsResult.Success).accounts
        assertEquals(2, accounts.size)
        assertEquals("Visa ••1111", accounts[0].label)
        assertTrue(accounts[0].isDefault)
        assertEquals("Mastercard ••2222", accounts[1].label)

        val setDefault = provider.setDefaultPaymentMethod("ca2")
        assertTrue(setDefault is PaymentActionResult.Success)
        val put = http.callsTo("charge-accounts-multi-provider").first()
        assertEquals("PUT", put.method)
        assertTrue((put.headers["Content-Type"] ?: "").toString().contains("messageType="))
        assertTrue((put.body as String).contains("\"charge_account_id\":\"ca2\""))

        val removed = provider.removePaymentMethod("ca 1/x")
        assertTrue(removed is PaymentActionResult.Success)
        assertTrue(http.callsTo("/chargeaccounts/ca%201%2Fx").isNotEmpty() ||
            http.callsTo("/chargeaccounts/").any { it.method == "DELETE" })
    }

    @Test
    fun addCardThroughStripeToken() = runTest {
        seedToken()
        val provider = LyftProvider(context)
        http.on("/v1/tokenization_strategies") {
            http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pk_test_123"}}]}""", url = it.url)
        }
        http.on("api.stripe.com/v1/tokens") {
            http.raw(200, """{"id":"tok_visa_1"}""", url = it.url)
        }
        http.on("/charge-accounts-multi-provider") { http.raw(200, accountsJson(), url = it.url) }
        val card = NewCard("4111111111111111", 12, 2030, "123", "94101")
        val result = provider.addCard(card, makeDefault = true)
        assertTrue(result is AddCardResult.Success, "$result")
        val stripeCall = http.callsTo("api.stripe.com").first()
        assertTrue((stripeCall.body as String).contains("card%5Bnumber%5D") ||
            (stripeCall.body as String).contains("card[number]=4111111111111111"))
        val create = http.callsTo("charge-accounts-multi-provider").first()
        val createBody = create.body as String
        assertTrue(createBody.contains("\"token\":\"tok_visa_1\""), createBody)
        assertTrue(createBody.contains("\"default\":true"), createBody)
    }

    @Test
    fun addCardSetupIntentAndBraintreePaths() = runTest {
        seedToken()
        val provider = LyftProvider(context)
        val card = NewCard("4111111111111111", 12, 2030, "123", "94101")
        // SetupIntent strategy.
        http.on("/v1/tokenization_strategies") {
            http.raw(
                200,
                """{"strategies":[{"stripe_card_setup_intent_data":{"api_key":"pk_si","client_secret":"seti_secret","setup_intent_id":"seti_1","stripe_api_version":"2024-06-20"}}]}""",
                url = it.url,
            )
        }
        http.on("setup_intents/seti_1/confirm") { http.raw(200, """{"payment_method":"pm_999"}""", url = it.url) }
        http.on("/charge-accounts-multi-provider") { http.raw(200, accountsJson(), url = it.url) }
        val si = provider.addCard(card, makeDefault = false)
        assertTrue(si is AddCardResult.Success, "$si")
        val confirmBody = http.callsTo("setup_intents").first().body as String
        assertTrue(confirmBody.contains("client_secret=seti_secret"), confirmBody)
        http.uninstall(); http.install()

        // Braintree strategy: client token is base64 JSON.
        val btToken = java.util.Base64.getEncoder().encodeToString(
            """{"authorizationFingerprint":"fp-1","graphQL":{"url":"https://payments.braintree-api.com/graphql"}}"""
                .toByteArray(),
        )
        http.on("/v1/tokenization_strategies") {
            http.raw(200, """{"strategies":[{"braintree_card_data":{"api_key":"$btToken"}}]}""", url = it.url)
        }
        http.on("payments.braintree-api.com") {
            http.raw(200, """{"data":{"tokenizeCreditCard":{"token":"nonce-abc"}}}""", url = it.url)
        }
        http.on("/charge-accounts-multi-provider") { http.raw(200, accountsJson(), url = it.url) }
        val bt = provider.addCard(card, makeDefault = false)
        assertTrue(bt is AddCardResult.Success, "$bt")
        val createBody = http.callsTo("charge-accounts-multi-provider").first().body as String
        assertTrue(createBody.contains("\"nonce\":\"nonce-abc\""), createBody)
        http.uninstall(); http.install()

        // Unsupported strategy -> Unsupported, no processor call.
        http.on("/v1/tokenization_strategies") {
            http.raw(200, """{"strategies":[{"adyen_card_data":{"api_key":"x"}}]}""", url = it.url)
        }
        assertEquals(AddCardResult.Unsupported, provider.addCard(card, makeDefault = false))
        // Processor error surfaces as Failed.
        http.uninstall(); http.install()
        http.on("/v1/tokenization_strategies") {
            http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pk"}}]}""", url = it.url)
        }
        http.on("api.stripe.com") {
            http.raw(402, """{"error":{"message":"Your card was declined."}}""", url = it.url)
        }
        val declined = provider.addCard(card, makeDefault = false)
        assertTrue(declined is AddCardResult.Failed)
        assertTrue((declined as AddCardResult.Failed).message.contains("declined"))
    }

    @Test
    fun tokenizerDirectBehaviors() = runTest {
        val session = LyftApiSession(context)
        val tokenizer = com.vayunmathur.taxi.network.lyft.LyftCardTokenizer(
            json, session.systemTrustFactory, session::authJsonHeaders,
        )
        // Config parsing prefers SetupIntent, then Stripe, then Braintree.
        val both = tokenizer.parseConfigJson(
            """{"strategies":[{"stripe_card_data":{"api_key":"pk1"}},
               {"stripe_card_setup_intent_data":{"api_key":"pk2","client_secret":"s","setup_intent_id":"seti"}}]}""",
        )
        assertEquals("stripe_setup_intent", both?.provider)
        assertEquals("pk2", both?.key)
        val stripeOnly = tokenizer.parseConfigJson("""{"strategies":[{"stripe_card_data":{"api_key":"pk1"}}]}""")
        assertEquals("stripe", stripeOnly?.provider)
        val btOnly = tokenizer.parseConfigJson("""{"strategies":[{"braintree_card_data":{"api_key":"bt"}}]}""")
        assertEquals("braintree", btOnly?.provider)
        assertNull(tokenizer.parseConfigJson("""{"strategies":[]}"""))
        assertNull(tokenizer.parseConfigJson("not json"))
        // Braintree client token: raw JSON and base64 JSON both parse.
        val rawPair = tokenizer.parseBraintreeClientToken(
            """{"authorizationFingerprint":"fp","graphQL":{"url":"https://gql.example"}}""",
        )
        assertEquals("https://gql.example" to "fp", rawPair)
        val defaultUrl = tokenizer.parseBraintreeClientToken("""{"authorizationFingerprint":"fp2"}""")
        assertEquals("https://payments.braintree-api.com/graphql", defaultUrl?.first)
        assertNull(tokenizer.parseBraintreeClientToken("""{"graphQL":{}}"""))
        assertNull(tokenizer.parseBraintreeClientToken("%%%not-base64%%%"))
        // Tokenize dispatch: unknown provider + missing SetupIntent fields.
        val card = NewCard("4111111111111111", 1, 2030, "123", "")
        val unknown = tokenizer.tokenize(
            com.vayunmathur.taxi.network.lyft.TokenizerConfig("adyen", "k"), card,
        )
        assertTrue(unknown is com.vayunmathur.taxi.network.lyft.TokenizeResult.Err)
        val missingSecret = tokenizer.tokenize(
            com.vayunmathur.taxi.network.lyft.TokenizerConfig("stripe_setup_intent", "k"), card,
        )
        assertTrue(missingSecret is com.vayunmathur.taxi.network.lyft.TokenizeResult.Err)
        assertTrue(
            (missingSecret as com.vayunmathur.taxi.network.lyft.TokenizeResult.Err)
                .message.contains("setup_intent_id"),
        )
        // parseConfig over a RawResponse picks JSON.
        val parsed = tokenizer.parseConfig(http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pk9"}}]}"""))
        assertEquals("pk9", parsed?.key)
    }

    // --- Exploratory + provider facade ---

    @Test
    fun exploratoryEndpointsReturnRawBodies() = runTest {
        seedToken()
        val provider = LyftProvider(context)
        http.defaultRaw = http.raw(200, """{"some":"thing"}""")
        assertEquals("""{"some":"thing"}""", provider.activeOffer())
        assertNotNull(provider.pickupGeofence("r/1"))
        assertTrue(http.callsTo("pickupgeofence").first().url.contains("r%2F1"))
        assertNotNull(provider.paymentDetails("r1"))
        assertNotNull(provider.updatedCostEstimate("r1"))
        assertNotNull(provider.scheduledRideTimeEstimates(pickup, dropoff))
        assertNotNull(provider.offeringsOverview(pickup, dropoff))
        assertNotNull(provider.updatePickup("r1", pickup))
        assertNotNull(provider.redispatch("r1"))
        // Non-2xx maps to null.
        http.defaultRaw = http.raw(500, "x", "text/plain")
        assertNull(provider.activeOffer())
    }

    @Test
    fun providerSignOutRevokesAndClears() = runTest {
        seedToken()
        http.performHandler = { http.simple(200, "", it.url) }
        val provider = LyftProvider(context)
        assertTrue(provider.isSignedIn())
        provider.signOut()
        assertFalse(provider.isSignedIn())
        assertEquals(2, http.callsTo("revoke_token").size)
    }

    @Test
    fun sessionAccessTokenRefreshesWhenExpired() = runTest {
        // Expired stored token triggers a refresh through LyftAuth.
        LyftTokenStore(context).save(LyftToken(accessToken = "old", refreshToken = "rt-old", expiresIn = 0))
        http.performHandler = {
            http.simple(200, """{"access_token":"brand-new","refresh_token":"rt-new","expires_in":3600}""", it.url)
        }
        val provider = LyftProvider(context)
        http.on("/chargeaccounts") { http.raw(200, accountsJson(), url = it.url) }
        val methods = provider.paymentMethods()
        assertTrue(methods is PaymentMethodsResult.Success)
        assertTrue(http.callsTo("/chargeaccounts").first().headers.toString().contains("brand-new"))
        // Session header helpers carry the configured identity.
        val session = LyftApiSession(context)
        assertTrue(session.authJsonHeaders("t")["Content-Type"] == "application/json")
        assertTrue(session.authProtoJsonHeaders("t", "pb.X")["Content-Type"]!!.contains("messageType=pb.X"))
        assertTrue(session.httpError(http.raw(422, "bad body")).contains("422"))
    }
}
