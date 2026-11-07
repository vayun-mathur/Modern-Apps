package com.vayunmathur.taxi.network

import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.RideStatus
import com.vayunmathur.taxi.network.lyft.LyftOffersParser
import com.vayunmathur.taxi.network.lyft.LyftRideParser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: the binary-protobuf response paths of the Lyft parsers, built
 * byte-by-byte with the same hand-rolled writer the reader is pinned
 * against. (The JSON paths are pinned by the pure tests.)
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class TaxiPass3ProtoParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- minimal protobuf writer (mirrors ProtoMessageTest) ---

    private fun varintBytes(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            var b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.add(b.toByte())
            if (v == 0L) break
        }
        return out.toByteArray()
    }

    private fun record(field: Int, wire: Int, payload: ByteArray): ByteArray =
        varintBytes(((field shl 3) or wire).toLong()) + payload

    private fun varintField(field: Int, value: Long): ByteArray = record(field, 0, varintBytes(value))

    private fun stringField(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return record(field, 2, varintBytes(bytes.size.toLong()) + bytes)
    }

    private fun messageField(field: Int, vararg parts: ByteArray): ByteArray {
        val bytes = parts.fold(byteArrayOf()) { a, p -> a + p }
        return record(field, 2, varintBytes(bytes.size.toLong()) + bytes)
    }

    private fun wrappedLong(field: Int, value: Long): ByteArray =
        messageField(field, varintField(1, value))

    private fun wrappedString(field: Int, value: String): ByteArray =
        messageField(field, stringField(1, value))

    private fun doubleField(field: Int, value: Double): ByteArray {
        val bits = java.lang.Double.doubleToLongBits(value)
        val le = ByteArray(8) { i -> ((bits ushr (8 * i)) and 0xFF).toByte() }
        return record(field, 1, le)
    }

    private fun raw(bytes: ByteArray, contentType: String = "application/x-protobuf"): RawResponse =
        RawResponse(200, "OK", bytes, mapOf("Content-Type" to listOf(contentType)), "https://api.lyft.com/x")

    // --- Offers (ReadOffersV2Response) ---

    @Test
    fun offersProtoParsesFullQuote() {
        val cost = messageField(
            4, // cost_estimate
            wrappedLong(6, 500), // cents_min
            wrappedLong(5, 900), // cents_max
            wrappedString(8, "USD"), // currency
            stringField(4, "Lyft"), // ride_type
            stringField(3, "ct-1"), // cost_token
            wrappedLong(22, 1200), // duration seconds
            doubleField(12, 1.5), // primetime multiplier
            wrappedLong(20, 9999), // cost token expiry (seconds)
        )
        val rideType = messageField(
            5, // ride_type_details
            varintField(7, 4), // seats
            messageField(10, stringField(3, "Lyft Standard")), // display_properties.name
        )
        val travel = messageField(
            9, // ride_travel_details
            messageField(1, messageField(2, wrappedLong(1, 240_000))), // pickup eta ms
        )
        val offer = cost + rideType + travel +
            stringField(1, "o1") + stringField(2, "lyft") + wrappedString(3, "tok")
        val offers = messageField(3, offer) + stringField(1, "ps1") + stringField(2, "or1")
        val root = messageField(1, offers)

        val parsed = LyftOffersParser(json).parseProto(root)
        assertEquals(1, parsed.quotes.size)
        assertEquals("ps1", parsed.purchaseSessionId)
        assertEquals("or1", parsed.offersResponseId)
        val q = parsed.quotes[0]
        assertEquals("Lyft Standard", q.displayName)
        assertEquals(500L, q.fareLowMinor)
        assertEquals(900L, q.fareHighMinor)
        assertEquals("USD", q.currency)
        assertEquals(4, q.pickupEtaMinutes)
        assertEquals(20, q.tripDurationMinutes)
        assertEquals(1.5, q.surgeMultiplier)
        assertEquals(4, q.capacity)
        assertEquals("o1", q.offerId)
        assertEquals("tok", q.offerToken)
        assertEquals("ct-1", q.costToken)
        assertEquals(9_999_000L, q.costTokenExpiryMs)

        // The RawResponse entry point negotiates proto by content type.
        val fromResp = LyftOffersParser(json).parseProto(root)
        assertEquals(1, fromResp.quotes.size)
        // Upfront-only and empty bodies.
        val upfrontCost = messageField(4, wrappedLong(7, 700))
        val upfrontOffer = upfrontCost + stringField(2, "lyft")
        val upfrontParsed = LyftOffersParser(json).parseProto(messageField(1, messageField(3, upfrontOffer)))
        assertEquals(700L, upfrontParsed.quotes[0].fareLowMinor)
        assertEquals(0, LyftOffersParser(json).parseProto(ByteArray(0)).quotes.size)
        assertEquals(0, LyftOffersParser(json).parseProto(byteArrayOf(0x7F)).quotes.size)
    }

    @Test
    fun offersProtoCouponDiscountApplies() {
        val coupon = messageField(18, varintField(10, 200), varintField(11, 300))
        val cost = messageField(
            4, wrappedLong(6, 500), wrappedLong(5, 900), coupon,
        )
        val offer = cost + stringField(2, "lyft")
        val parsed = LyftOffersParser(json).parseProto(messageField(1, messageField(3, offer)))
        val q = parsed.quotes[0]
        assertEquals(300L, q.fareLowMinor)
        assertEquals(600L, q.fareHighMinor)
        assertEquals(500L, q.originalFareLowMinor)
        assertTrue(q.hasDiscount)
    }

    // --- Active ride (PassengerRide) ---

    @Test
    fun rideProtoParsesDriverVehicleStopsAndLocation() {
        val driver = messageField(
            6, stringField(5, "Jo"), stringField(6, "Smith"),
            stringField(8, "+1555"), doubleField(9, 4.9),
        )
        val vehicle = messageField(
            8, stringField(1, "Toyota"), stringField(2, "Camry"),
            stringField(3, "XYZ"), stringField(6, "Black"),
        )
        val stop1 = messageField(
            9,
            messageField(2, doubleField(1, 37.7), doubleField(2, -122.4), stringField(5, "Home")),
            stringField(3, "pickup"), varintField(6, 120), varintField(4, 0),
        )
        val stop2 = messageField(
            9,
            messageField(2, doubleField(1, 37.8), doubleField(2, -122.5), stringField(5, "Work")),
            stringField(3, "dropoff"), varintField(6, 600), varintField(4, 1),
        )
        val location = messageField(11, doubleField(1, 37.71), doubleField(2, -122.41), doubleField(3, 90.0))
        val ride = stringField(1, "r9") + stringField(2, "accepted") + driver + vehicle + stop1 + stop2 + location

        val parsed = LyftRideParser(json).parseActiveRide(raw(ride))
        assertNotNull(parsed)
        assertEquals("r9", parsed.rideId)
        assertEquals(RideStatus.ACCEPTED, parsed.status)
        assertEquals("Jo Smith", parsed.driver?.displayName)
        assertEquals(4.9, parsed.driver?.rating)
        assertEquals("Black Toyota Camry", parsed.vehicle?.description)
        assertEquals("XYZ", parsed.vehicle?.licensePlate)
        assertEquals(37.71, parsed.driverLocation?.latitude)
        assertEquals(90.0, parsed.driverLocation?.bearing)
        assertEquals(2, parsed.stops.size)
        assertEquals("Home", parsed.stops[0].name)
        assertTrue(parsed.stops[0].isPickup)
        assertTrue(parsed.stops[1].completed)
        assertEquals(120, parsed.pickupEtaSeconds)

        // Driver location alone (bare location message), and malformed input.
        val bareLocation = doubleField(1, 37.71) + doubleField(2, -122.41) + doubleField(3, 90.0)
        val loc = LyftRideParser(json).parseDriverLocation(raw(bareLocation))
        assertEquals(37.71, loc?.latitude)
        assertNull(LyftRideParser(json).parseDriverLocation(raw(byteArrayOf(1, 2))))
        assertNull(LyftRideParser(json).toDriverLocationProto(
            com.vayunmathur.taxi.network.lyft.ProtoMessage(byteArrayOf(), 0, 0),
        ))
    }

    // --- Charge accounts proto ---

    @Test
    fun chargeAccountsProtoParsesAccounts() {
        val session = com.vayunmathur.taxi.network.lyft.LyftApiSession(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
        val parser = com.vayunmathur.taxi.network.lyft.LyftChargeAccountsParser(session)
        fun account(id: String, kind: String?, label: String?, lastFour: String?, isDefault: Boolean): ByteArray {
            var bytes = wrappedString(1, id)
            if (kind != null) bytes += wrappedString(2, kind)
            bytes += messageField(3, varintField(1, if (isDefault) 1 else 0))
            if (label != null) bytes += wrappedString(5, label)
            if (lastFour != null) bytes += wrappedString(10, lastFour)
            return messageField(1, bytes)
        }
        val body = account("ca1", "Visa", null, "1111", isDefault = true) +
            account("ca2", null, "Business Card", null, isDefault = false)
        val accounts = parser.parse(raw(body))
        assertEquals(2, accounts.size)
        assertEquals("Visa ••1111", accounts[0].label)
        assertTrue(accounts[0].isDefault)
        assertEquals("Business Card", accounts[1].label)
        // Garbage degrades to an empty list via the JSON fallback.
        assertEquals(emptyList(), parser.parse(raw(byteArrayOf(0x55, 0x01))))
    }
}
