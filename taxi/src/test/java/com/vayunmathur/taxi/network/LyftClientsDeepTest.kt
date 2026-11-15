package com.vayunmathur.taxi.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.CancelResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.RideStatusResult
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.network.ProtoWire as W
import com.vayunmathur.taxi.network.lyft.LyftApiSession
import com.vayunmathur.taxi.network.lyft.LyftChargeAccountsParser
import com.vayunmathur.taxi.network.lyft.LyftExploratoryApi
import com.vayunmathur.taxi.network.lyft.LyftPaymentsClient
import com.vayunmathur.taxi.network.lyft.LyftProvider
import com.vayunmathur.taxi.network.lyft.LyftQuotesClient
import com.vayunmathur.taxi.network.lyft.LyftRidesClient
import com.vayunmathur.taxi.network.lyft.LyftToken
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyftClientsDeepTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val http = TaxiPass3FakeHttp()
    private val pickup = Place("Home", "1 Main St", LatLng(37.7749, -122.4194))
    private val dropoff = Place("Work", null, LatLng(37.8044, -122.2712))
    private val session get() = LyftApiSession(context)

    private fun seed() = runBlocking {
        LyftTokenStore(context).save(LyftToken(accessToken = "access-tok", refreshToken = "rt", expiresIn = 3600, userId = "42"))
    }

    @BeforeTest fun setUp() { http.install(); runBlocking { LyftTokenStore(context).clear() } }
    @AfterTest fun tearDown() { http.uninstall(); runBlocking { LyftTokenStore(context).clear() } }

    private val offersJson = """{"offers":{"purchase_session_id":"ps1","offers_response_id":"or1","offers_list":[
      {"offer_product_id":"lyft","id":"o1","cost_estimate":{"ride_type":"Lyft","currency":"USD","estimated_cost_cents_min":500,"estimated_cost_cents_max":900},
       "ride_type_details":{"display_properties":{"name":"Lyft Standard"},"seats":4}}]}}"""

    // ---- Quotes ----
    @Test fun offersBodyEncodesE6AndOptionalIds() {
        val client = LyftQuotesClient(session)
        val body = client.offersBody(pickup, dropoff, null, null)
        assertTrue(body.contains("\"latitude_e6\":37774900"), body)
        assertTrue(body.contains("\"longitude_e6\":-122419400"), body)
        assertTrue(body.contains("OFFER_SELECTOR")); assertFalse(body.contains("last_offers_id"))
        val body2 = client.offersBody(pickup, dropoff, "last1", "sess1")
        assertTrue(body2.contains("\"last_offers_id\":\"last1\"")); assertTrue(body2.contains("\"purchase_session_id\":\"sess1\""))
        // negative / zero coords
        val zero = client.offersBody(Place("Z", null, LatLng(0.0, 0.0)), dropoff, null, null)
        assertTrue(zero.contains("\"latitude_e6\":0"))
    }

    @Test fun quotesNotSignedInSuccessFailedAndEmpty() = runTest {
        val client = LyftQuotesClient(session)
        assertEquals(QuoteResult.NotSignedIn, client.quotes(pickup, dropoff))
        assertTrue(http.calls.isEmpty())
        seed()
        http.on("/v2/offerings") { http.raw(200, offersJson, url = it.url) }
        val ok = client.quotes(pickup, dropoff) as QuoteResult.Success
        assertEquals(1, ok.quotes.size); assertEquals("ps1", ok.purchaseSessionId)
        assertTrue(http.callsTo("/v2/offerings").single().headers.toString().contains("access-tok"))
        http.uninstall(); http.install()
        http.on("/v2/offerings") { http.raw(503, "unavailable", "text/plain", it.url) }
        assertTrue((client.quotes(pickup, dropoff) as QuoteResult.Failed).message.contains("503"))
        http.uninstall(); http.install()
        http.on("/v2/offerings") { http.raw(200, """{"offers":{"offers_list":[]}}""", url = it.url) }
        assertTrue((client.quotes(pickup, dropoff) as QuoteResult.Failed).message.contains("no offers"))
    }

    @Test fun updateQuotesUsesUpdateEndpoint() = runTest {
        seed()
        http.on("/v2/offerings/update") { http.raw(200, offersJson, url = it.url) }
        val client = LyftQuotesClient(session)
        val ok = client.updateQuotes(pickup, dropoff, "or1", "ps1") as QuoteResult.Success
        assertEquals(1, ok.quotes.size)
        assertTrue(http.callsTo("/v2/offerings/update").single().body.toString().contains("last_offers_id"))
    }

    @Test fun quotesProtoResponseParses() = runTest {
        seed()
        // build minimal offers proto
        val cost = W.concat(W.wrappedLong(6, 700), W.wrappedLong(5, 700), W.stringField(4, "Lyft"))
        val offer = W.concat(W.stringField(2, "lyft"), W.msg(4, cost), W.msg(5, W.msg(10, W.stringField(3, "ProtoQ"))))
        val offersMsg = W.concat(W.stringField(1, "psp"), W.bytesField(3, offer))
        val bytes = W.msg(1, offersMsg)
        http.on("/v2/offerings") {
            com.vayunmathur.library.network.RawResponse(200, "OK", bytes, mapOf("Content-Type" to listOf("application/x-protobuf")), it.url)
        }
        val ok = LyftQuotesClient(session).quotes(pickup, dropoff) as QuoteResult.Success
        assertEquals("ProtoQ", ok.quotes.single().displayName); assertEquals(700L, ok.quotes.single().fareLowMinor)
    }

    // ---- Exploratory ----
    @Test fun exploratorySignedOutReturnsNullWithoutCalls() = runTest {
        val api = LyftExploratoryApi(session, { _, _ -> "{}" }, { "{}" })
        assertNull(api.activeOffer()); assertNull(api.pickupGeofence("r 1")); assertNull(api.paymentDetails("r1"))
        assertNull(api.updatedCostEstimate("r1")); assertNull(api.scheduledRideTimeEstimates(pickup, dropoff))
        assertNull(api.offeringsOverview(pickup, dropoff)); assertNull(api.updatePickup("r1", pickup)); assertNull(api.redispatch("r1"))
        assertTrue(http.calls.isEmpty())
    }

    @Test fun exploratorySignedInGetPostAndErrors() = runTest {
        seed()
        http.on("/v1/active-offer") { http.raw(200, "{\"offer\":1}", url = it.url) }
        http.on("pickupgeofence") { http.raw(200, "geo", "text/plain", it.url) }
        http.on("paymentdetails") { http.raw(500, "e", "text/plain", it.url) }
        http.on("updated-cost-estimate") { http.raw(200, "cost!", "text/plain", it.url) }
        http.on("scheduledridetimeestimates") { http.raw(200, "sched", "text/plain", it.url) }
        http.on("offerings/overview") { http.raw(200, "overview", "text/plain", it.url) }
        http.on("/v1/rides/r9/pickup") { http.raw(200, "moved", "text/plain", it.url) }
        http.on("redispatch") { http.raw(200, "redispatched", "text/plain", it.url) }
        val api = LyftExploratoryApi(session, { _, _ -> "OFFERS_BODY" }, { "LOC_BODY" })
        assertEquals("{\"offer\":1}", api.activeOffer())
        assertEquals("geo", api.pickupGeofence("r 1")) // encoded id still routes
        assertTrue(http.calls.any { it.url.contains("r%201") || it.url.contains("r+1") || it.url.contains("%20") })
        assertNull(api.paymentDetails("r1"))
        assertEquals("cost!", api.updatedCostEstimate("r1"))
        assertEquals("sched", api.scheduledRideTimeEstimates(pickup, dropoff))
        assertEquals("overview", api.offeringsOverview(pickup, dropoff))
        assertEquals("moved", api.updatePickup("r9", pickup))
        assertEquals("redispatched", api.redispatch("r1"))
        assertTrue(http.callsTo("redispatch").single().body.toString().contains("r1"))
        // long body truncated to 4000
        http.uninstall(); http.install()
        http.on("/v1/active-offer") { http.raw(200, "x".repeat(5000), "text/plain", it.url) }
        assertEquals(4000, api.activeOffer()!!.length)
    }

    // ---- Rides ----
    private fun quote(offerId: String? = "o1") = RideQuote(
        Provider.LYFT, "lyft", "Lyft Standard", 1200, 1500, "USD", 4, 20, null, 4,
        offerId = offerId, offerToken = "tok", costToken = "ct", rideType = "Lyft",
    )

    @Test fun ridesCreateDryRunMissingOfferAndSignedOut() = runTest {
        val client = LyftRidesClient(session) { false }
        // signed out first (token check precedes offer check? accessToken null -> Failed not signed in)
        assertTrue((client.createRide(quote(), pickup, dropoff, null, false) as BookingResult.Failed).message.contains("Not signed in"))
        seed()
        assertTrue((client.createRide(quote(null), pickup, dropoff, null, false) as BookingResult.Failed).message.contains("no offer id"))
        val dry = client.createRide(quote(), pickup, dropoff, ChargeAccount("ca1", null, "Visa", true), false) as BookingResult.DryRun
        assertTrue(dry.requestJson.contains("\"offer_id\":\"o1\""))
        assertTrue(dry.requestJson.contains("\"charge_token\":\"ca1\"")) // falls back to id
        assertTrue(dry.requestJson.contains("lat_microdegrees"))
        assertTrue(http.callsTo("core_trips").isEmpty())
        // dryRun flag even when live
        val liveClient = LyftRidesClient(session) { true }
        assertTrue(liveClient.createRide(quote(), pickup, dropoff, null, true) is BookingResult.DryRun)
    }

    @Test fun ridesCreateLiveJsonAndProtoIds() = runTest {
        seed()
        http.on("core_trips/create") { http.raw(200, """{"trip_details":{"trip_id":"888"}}""", url = it.url) }
        val client = LyftRidesClient(session) { true }
        val created = client.createRide(quote(), pickup, dropoff, null, false) as BookingResult.Created
        assertEquals("888", created.rideId)
        http.uninstall(); http.install()
        // proto trip_details(1) -> varint trip_id(1) -> string form
        val protoBody = W.msg(1, W.varintField(1, 777))
        http.on("core_trips/create") {
            com.vayunmathur.library.network.RawResponse(200, "OK", protoBody, mapOf("Content-Type" to listOf("application/x-protobuf")), it.url)
        }
        assertEquals("777", (client.createRide(quote(), pickup, dropoff, null, false) as BookingResult.Created).rideId)
        http.uninstall(); http.install()
        http.on("core_trips/create") { http.raw(500, "boom", "text/plain", it.url) }
        assertTrue((client.createRide(quote(), pickup, dropoff, null, false) as BookingResult.Failed).message.contains("500"))
    }

    @Test fun ridesActiveDriverCancelById() = runTest {
        val client = LyftRidesClient(session) { true }
        assertTrue((client.activeRide() as RideStatusResult.Failed).message.contains("Not signed in"))
        assertNull(client.driverLocation("r1"))
        assertTrue((client.cancelRide("r1") as CancelResult.Failed).message.contains("Not signed in"))
        seed()
        http.on("/v1/activeride") { http.raw(404, "none", "text/plain", it.url) }
        assertEquals(RideStatusResult.None, client.activeRide())
        http.uninstall(); http.install()
        http.on("/v1/activeride") { http.raw(200, """{"ride_id":"r5","status":"accepted","driver":{"first_name":"Jo"}}""", url = it.url) }
        assertEquals("r5", (client.activeRide() as RideStatusResult.Active).ride.rideId)
        http.uninstall(); http.install()
        http.on("/v1/activeride") { http.raw(200, """{}""", url = it.url) }
        assertEquals(RideStatusResult.None, client.activeRide()) // no content
        http.on("driver-location") { http.raw(200, """{"lat":3.0,"lng":4.0}""", url = it.url) }
        assertEquals(3.0, client.driverLocation("r 5")?.latitude)
        http.on("/cancel") { http.raw(200, "cancelled ok", "text/plain", it.url) }
        assertEquals(CancelResult.Done("cancelled ok"), client.cancelRide("r5"))
        http.uninstall(); http.install()
        http.on("/cancel") { http.raw(500, "fee error", "text/plain", it.url) }
        assertTrue((client.cancelRide("r5") as CancelResult.Failed).message.contains("500"))
        http.on("/v1/rides/r7") { http.raw(200, """{"ride_id":"r7","status":"arrived"}""", url = it.url) }
        assertEquals("r7", (client.rideById("r7") as RideStatusResult.Active).ride.rideId)
        http.uninstall(); http.install()
        http.on("/v1/rides/r7") { http.raw(404, "", "text/plain", it.url) }
        assertEquals(RideStatusResult.None, client.rideById("r7"))
        // locationV2 body
        val locBody = client.locationV2Body(pickup)
        assertTrue(locBody.contains("37774900") && locBody.contains("Home") && locBody.contains("1 Main St"))
        assertFalse(client.locationV2Body(dropoff).contains("display_address"))
    }

    // ---- Payments + charge accounts parser ----
    @Test fun paymentsListSetDefaultRemove() = runTest {
        val client = LyftPaymentsClient(session)
        assertEquals(PaymentMethodsResult.NotSignedIn, client.paymentMethods())
        seed()
        val accountsJson = """{"chargeAccounts":[{"id":"ca1","label":"Visa ••1111","default":true},{"id":"ca2","kind":"Mastercard","lastFour":"2222"}]}"""
        http.on("/chargeaccounts") { http.raw(200, accountsJson, url = it.url) }
        http.on("charge-accounts-multi-provider") { http.raw(200, accountsJson, url = it.url) }
        val list = (client.paymentMethods() as PaymentMethodsResult.Success).accounts
        assertEquals(2, list.size); assertEquals("Visa ••1111", list[0].label); assertTrue(list[0].isDefault)
        assertEquals("Mastercard ••2222", list[1].label)
        val def = client.setDefaultPaymentMethod("ca2")
        assertTrue(def is com.vayunmathur.taxi.data.PaymentActionResult.Success)
        assertTrue(http.callsTo("charge-accounts-multi-provider").single().body.toString().contains("\"charge_account_id\":\"ca2\""))
        assertTrue(client.removePaymentMethod("ca 1") is com.vayunmathur.taxi.data.PaymentActionResult.Success)
        assertTrue(http.calls.any { it.method == "DELETE" })
        http.uninstall(); http.install()
        http.on("/chargeaccounts") { http.raw(500, "bad", "text/plain", it.url) }
        assertTrue((client.paymentMethods() as PaymentMethodsResult.Failed).message.contains("500"))
        http.on("charge-accounts-multi-provider") { http.raw(422, "invalid", "text/plain", it.url) }
        assertTrue(client.setDefaultPaymentMethod("x") is com.vayunmathur.taxi.data.PaymentActionResult.Failed)
    }

    @Test fun chargeAccountsParserJsonLabelsAndProto() {
        val parser = LyftChargeAccountsParser(session)
        fun resp(text: String) = http.raw(200, text, url = "")
        // label fallbacks via JSON array variants
        val parsed = parser.parse(resp("""{"charge_accounts":[
          {"id":"a","kind":"Visa","last_four":"4242"},
          {"id":"b","lastFour":"9999"},
          {"id":"c","kind":"Amex"},
          {"id":"d"},
          {"kind":"NoId"}]}"""))
        assertEquals(listOf("Visa ••4242", "•• 9999", "Amex", "Card"), parsed.map { it.label })
        assertEquals(0, parser.parse(resp("not json")).size)
        // proto: root field1 repeated account; account id1 wrapped, kind2, default3 wrapped, label5, lastFour10
        fun acct(id: String, label: String?): ByteArray {
            val parts = mutableListOf(W.wrappedString(1, id), W.wrappedBool(3, true), W.wrappedString(10, "1111"))
            label?.let { parts += W.wrappedString(5, it) }
            return W.concat(*parts.toTypedArray())
        }
        val protoBytes = W.concat(W.bytesField(1, acct("p1", "Proto Visa")), W.bytesField(1, acct("p2", null)))
        val protoResp = com.vayunmathur.library.network.RawResponse(200, "OK", protoBytes, mapOf("Content-Type" to listOf("application/x-protobuf")), "")
        val protoParsed = parser.parse(protoResp)
        assertEquals(2, protoParsed.size); assertEquals("Proto Visa", protoParsed[0].label); assertTrue(protoParsed[0].isDefault)
        assertEquals("•• 1111", protoParsed[1].label)
    }

    @Test fun paymentsAddCardUnsupportedAndSuccess() = runTest {
        seed()
        val client = LyftPaymentsClient(session)
        val card = NewCard("4111111111111111", 12, 2030, "123", "94101")
        // no strategy -> Unsupported
        http.on("tokenization_strategies") { http.raw(200, """{"strategies":[]}""", url = it.url) }
        assertEquals(com.vayunmathur.taxi.data.AddCardResult.Unsupported, client.addCard(card, false))
        http.uninstall(); http.install()
        http.on("tokenization_strategies") { http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pk"}}]}""", url = it.url) }
        http.on("api.stripe.com") { http.raw(200, """{"id":"tok_new"}""", url = it.url) }
        http.on("charge-accounts-multi-provider") { http.raw(200, """{"chargeAccounts":[{"id":"ca9","label":"Visa ••1111","default":true}]}""", url = it.url) }
        val ok = client.addCard(card, true) as com.vayunmathur.taxi.data.AddCardResult.Success
        assertEquals("ca9", ok.accounts!!.single().id)
        val createCall = http.callsTo("charge-accounts-multi-provider").single { it.method == "POST" }
        assertTrue(createCall.body.toString().contains("tok_new"))
        assertTrue(createCall.body.toString().contains("\"default\":true"))
        // tokenize failure -> Failed
        http.uninstall(); http.install()
        http.on("tokenization_strategies") { http.raw(200, """{"strategies":[{"stripe_card_data":{"api_key":"pk"}}]}""", url = it.url) }
        http.on("api.stripe.com") { http.raw(402, """{"error":{"message":"declined"}}""", url = it.url) }
        assertEquals("declined", (client.addCard(card, false) as com.vayunmathur.taxi.data.AddCardResult.Failed).message)
    }

    // ---- LyftProvider facade ----
    @Test fun providerFacadeDelegatesAndBookingUri() = runTest {
        val provider = LyftProvider(context)
        assertEquals(Provider.LYFT, provider.provider)
        assertFalse(provider.isSignedIn())
        assertEquals(QuoteResult.NotSignedIn, provider.quotes(pickup, dropoff))
        assertNull(provider.activeOffer()); assertNull(provider.driverLocation("r1"))
        val uri = provider.bookingUri(pickup, dropoff, quote())
        assertTrue(uri.contains("lyft.com/ride"), uri); assertTrue(uri.contains("id=lyft"))
        seed()
        http.on("/v2/offerings") { http.raw(200, offersJson, url = it.url) }
        assertTrue(provider.quotes(pickup, dropoff) is QuoteResult.Success)
        assertTrue(provider.updateQuotes(pickup, dropoff, null, null) is QuoteResult.Failed || provider.updateQuotes(pickup, dropoff, null, null) is QuoteResult.Success)
        assertTrue(LyftProvider.BOOKING_LIVE)
    }
}
