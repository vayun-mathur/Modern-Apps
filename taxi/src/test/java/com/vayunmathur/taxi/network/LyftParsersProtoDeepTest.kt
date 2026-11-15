package com.vayunmathur.taxi.network

import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.network.ProtoWire as W
import com.vayunmathur.taxi.network.lyft.LyftOffersParser
import com.vayunmathur.taxi.network.lyft.LyftRideParser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyftParsersProtoDeepTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val offers = LyftOffersParser(json)
    private val rides = LyftRideParser(json)

    private fun raw(bytes: ByteArray, ct: String): RawResponse =
        RawResponse(200, "OK", bytes, mapOf("Content-Type" to listOf(ct)), "https://x")
    private fun rawText(text: String, ct: String = "application/json"): RawResponse =
        raw(text.toByteArray(), ct)

    // ---- Offers proto ----
    private fun costProto(min: Long? = 500, max: Long? = 900, upfront: Long? = null): ByteArray {
        val parts = mutableListOf<ByteArray>()
        parts += W.stringField(3, "ctok")
        parts += W.stringField(4, "Lyft")
        max?.let { parts += W.wrappedLong(5, it) }
        min?.let { parts += W.wrappedLong(6, it) }
        upfront?.let { parts += W.wrappedLong(7, it) }
        parts += W.wrappedString(8, "EUR")
        parts += W.doubleField(12, 1.25)
        parts += W.msg(18, W.varintField(10, 100), W.varintField(11, 200))
        parts += W.wrappedLong(20, 1700)
        parts += W.wrappedLong(22, 1260)
        return W.concat(*parts.toTypedArray())
    }

    private fun offerProto(name: String? = "Proto Lyft", withCost: Boolean = true): ByteArray {
        val parts = mutableListOf<ByteArray>()
        parts += W.stringField(1, "offer-1")
        parts += W.stringField(2, "lyft")
        parts += W.wrappedString(3, "otok")
        if (withCost) parts += W.msg(4, costProto())
        val rideType = mutableListOf<ByteArray>()
        rideType += W.varintField(7, 4)
        if (name != null) rideType += W.msg(10, W.stringField(3, name))
        parts += W.msg(5, *rideType.toTypedArray())
        // travel 9 -> pickup 1 -> duration_range 2 -> wrapped 240000
        parts += W.msg(9, W.msg(1, W.msg(2, W.msg(1, W.varintField(1, 240000)))))
        return W.concat(*parts.toTypedArray())
    }

    private fun offersProto(vararg offerBytes: ByteArray): ByteArray {
        val offersParts = mutableListOf<ByteArray>()
        offersParts += W.stringField(1, "ps-proto")
        offersParts += W.stringField(2, "or-proto")
        offerBytes.forEach { offersParts += W.bytesField(3, it) }
        return W.msg(1, *offersParts.toTypedArray())
    }

    @Test fun offersProtoMapsEveryFieldWithCoupon() {
        val parsed = offers.parseProto(offersProto(offerProto()))
        assertEquals("ps-proto", parsed.purchaseSessionId)
        assertEquals("or-proto", parsed.offersResponseId)
        assertEquals(1, parsed.quotes.size)
        val q = parsed.quotes[0]
        assertEquals(Provider.LYFT, q.provider)
        assertEquals("Proto Lyft", q.displayName)
        assertEquals(400L, q.fareLowMinor) // 500-100
        assertEquals(700L, q.fareHighMinor) // 900-200
        assertEquals(500L, q.originalFareLowMinor)
        assertEquals(900L, q.originalFareHighMinor)
        assertEquals("EUR", q.currency)
        assertEquals(4, q.pickupEtaMinutes)
        assertEquals(21, q.tripDurationMinutes)
        assertEquals(1.25, q.surgeMultiplier)
        assertEquals(4, q.capacity)
        assertEquals("offer-1", q.offerId)
        assertEquals("otok", q.offerToken)
        assertEquals("ctok", q.costToken)
        assertEquals(1_700_000L, q.costTokenExpiryMs)
        assertTrue(q.hasDiscount)
    }

    @Test fun offersProtoEmptyAndMissingWrapper() {
        assertEquals(0, offers.parseProto(byteArrayOf()).quotes.size)
        assertEquals(0, offers.parseProto(W.stringField(9, "junk")).quotes.size)
        // offers wrapper present but no list
        val parsed = offers.parseProto(W.msg(1, W.stringField(1, "only-ps")))
        assertEquals("only-ps", parsed.purchaseSessionId)
        assertEquals(0, parsed.quotes.size)
    }

    @Test fun offersProtoUpfrontOnlyAndFallbackNames() {
        val cost = W.concat(W.wrappedLong(7, 2500), W.stringField(4, "LuxType"))
        val offer = W.concat(
            W.stringField(2, "prod-x"),
            W.msg(4, cost),
            // no display name: falls back to cost ride_type
            W.msg(5, W.varintField(7, 2)),
        )
        val q = offers.parseProto(offersProto(offer)).quotes.single()
        assertEquals("LuxType", q.displayName)
        assertEquals(2500L, q.fareLowMinor); assertEquals(2500L, q.fareHighMinor)
        assertFalse(q.hasDiscount)
        // No cost at all dropped, no name dropped
        assertEquals(0, offers.parseProto(offersProto(offerProto(withCost = false))).quotes.size)
        val noName = W.concat(W.stringField(2, "p"), W.msg(4, W.wrappedLong(7, 100)))
        // has product id as fallback name
        assertEquals("p", offers.parseProto(offersProto(noName)).quotes.single().displayName)
    }

    @Test fun farePriceBranches() {
        assertNull(offers.farePrice(null, null, null, null, null))
        assertEquals(100L, offers.farePrice(100, null, null, null, null)!!.low)
        assertEquals(200L, offers.farePrice(null, 200, null, null, null)!!.high)
        // negative discounts clamped, discount exceeding price clamps to 0
        val f = offers.farePrice(100, 100, null, -50, 999)!!
        assertEquals(100L, f.low); assertEquals(0L, f.high)
        val noDisc = offers.farePrice(100, 200, null, 0, 0)!!
        assertNull(noDisc.originalLow)
    }

    @Test fun offersJsonStringNumbersAndDefaults() {
        val raw = """{"offers":{"offers_list":[{"offer_product_id":"lyft","cost_estimate":{"estimated_cost_cents_min":"300","estimated_cost_cents_max":"400"}}]}}"""
        val q = offers.parseJson(raw).quotes.single()
        assertEquals(300L, q.fareLowMinor); assertEquals("USD", q.currency)
        assertEquals(0, offers.parseJson("[]").quotes.size)
        assertEquals(0, offers.parseJson("""{"offers":{}}""").quotes.size)
    }

    // ---- Ride proto ----
    private fun rideProto(): ByteArray {
        val driver = W.concat(
            W.stringField(5, "Jo"), W.stringField(6, "Smith"), W.stringField(7, "img"),
            W.stringField(8, "+1555"), W.doubleField(9, 4.8),
        )
        val vehicle = W.concat(
            W.stringField(1, "Toyota"), W.stringField(2, "Camry"), W.stringField(3, "XYZ"),
            W.stringField(4, "vimg"), W.stringField(6, "Black"),
        )
        val loc = W.concat(W.doubleField(1, 37.7), W.doubleField(2, -122.4), W.doubleField(3, 90.0))
        val place = W.concat(W.doubleField(1, 37.7), W.doubleField(2, -122.4), W.stringField(3, "1 Main"), W.stringField(5, "Home"))
        val stop = W.concat(
            W.msg(2, place), W.stringField(3, "pickup"), W.varintField(4, 0), W.varintField(6, 180),
        )
        val stop2 = W.concat(
            W.msg(7, place), W.stringField(3, "dropoff"), W.varintField(4, 1), W.varintField(6, 900),
        )
        return W.concat(
            W.stringField(1, "ride-proto"), W.stringField(2, "approaching"),
            W.msg(6, driver), W.msg(8, vehicle), W.bytesField(9, stop), W.bytesField(9, stop2), W.msg(11, loc),
        )
    }

    @Test fun rideProtoMapsDriverVehicleStopsLocation() {
        val ride = rides.toActiveRideProto(rideProto())
        assertEquals("ride-proto", ride.rideId)
        assertEquals(RideStatus.APPROACHING, ride.status)
        assertEquals("Jo Smith", ride.driver?.displayName)
        assertEquals(4.8, ride.driver?.rating)
        assertEquals("Black Toyota Camry", ride.vehicle?.description)
        assertEquals("XYZ", ride.vehicle?.licensePlate)
        assertEquals(37.7, ride.driverLocation?.latitude)
        assertEquals(90.0, ride.driverLocation?.bearing)
        assertEquals(2, ride.stops.size)
        assertEquals(180, ride.pickupEtaSeconds)
        assertTrue(ride.stops[0].isPickup); assertTrue(ride.stops[1].isDropoff)
        assertTrue(ride.stops[1].completed)
        assertEquals("Home", ride.stops[0].name)
        assertTrue(ride.hasContent)
    }

    @Test fun rideProtoEmptyHasNoContent() {
        val ride = rides.toActiveRideProto(byteArrayOf())
        assertNull(ride.rideId); assertEquals(RideStatus.UNKNOWN, ride.status)
        assertFalse(ride.hasContent); assertEquals(emptyList(), ride.stops)
    }

    @Test fun parseActiveRideDispatchAndFallback() {
        val protoResp = raw(rideProto(), "application/x-protobuf")
        assertEquals("ride-proto", rides.parseActiveRide(protoResp)?.rideId)
        val jsonResp = rawText("""{"ride_id":"j1","status":"arrived"}""")
        assertEquals("j1", rides.parseActiveRide(jsonResp)?.rideId)
        assertEquals(RideStatus.ARRIVED, rides.parseActiveRide(jsonResp)?.status)
        // proto content-type but JSON body -> fallback to json
        val lying = rawText("""{"ride_id":"j2","status":"pending"}""", "application/x-protobuf")
        assertEquals("j2", rides.parseActiveRide(lying)?.rideId)
        // empty both -> null or empty ride (hasContent false path)
        assertFalse(rides.parseActiveRide(rawText("{}"))?.hasContent ?: false)
    }

    @Test fun driverLocationJsonAndProtoDispatch() {
        val jsonResp = rawText("""{"location":{"lat":1.5,"lng":2.5,"bearing":45.0}}""")
        assertEquals(1.5, rides.parseDriverLocation(jsonResp)?.latitude)
        // bare json root
        assertEquals(2.5, rides.parseDriverLocation(rawText("""{"lat":1.0,"lng":2.5}"""))?.longitude)
        // proto: wrapper field1 = location msg
        val locMsg = W.concat(W.doubleField(1, 10.0), W.doubleField(2, 20.0))
        val wrapped = W.msg(1, locMsg)
        assertEquals(10.0, rides.parseDriverLocation(raw(wrapped, "application/x-protobuf"))?.latitude)
        // bare proto location
        assertEquals(20.0, rides.parseDriverLocation(raw(locMsg, "application/octet-stream"))?.longitude)
        assertNull(rides.toDriverLocationProto(com.vayunmathur.taxi.network.lyft.ProtoMessage(byteArrayOf(), 0, 0)))
    }

    @Test fun stopJsonVariants() {
        val parser = rides
        val noCoords = kotlinx.serialization.json.Json.parseToJsonElement("""{"location":{"place_name":"X"},"kind":"pickup"}""") as kotlinx.serialization.json.JsonObject
        assertNull(parser.toStopJson(noCoords).location)
        assertEquals("X", parser.toStopJson(noCoords).name)
    }
}
