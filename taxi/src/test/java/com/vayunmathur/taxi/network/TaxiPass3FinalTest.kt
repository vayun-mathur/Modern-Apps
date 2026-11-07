package com.vayunmathur.taxi.network

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RemoteImageFetchers
import com.vayunmathur.taxi.data.uber.UberSession
import com.vayunmathur.taxi.network.lyft.LyftApiSession
import com.vayunmathur.taxi.network.lyft.LyftCardTokenizer
import com.vayunmathur.taxi.network.lyft.TokenizeResult
import com.vayunmathur.taxi.network.uber.UberProvider
import com.vayunmathur.taxi.platform.location.LocationProvider
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3 (final gaps): UberProvider, remote image fetching, the TaxiHttp
 * production delegation to a loopback server, last-known-location, and
 * the card tokenizer's protobuf/failure paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3FinalTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val http = TaxiPass3FakeHttp()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var server: HttpServer? = null

    @BeforeTest
    fun setUp() {
        http.install()
    }

    @AfterTest
    fun tearDown() {
        http.uninstall()
        server?.stop(0)
        server = null
        runBlocking { UberSession(context).clear() }
    }

    private val pickup = Place("Home", null, LatLng(37.7749, -122.4194))
    private val dropoff = Place("Work", null, LatLng(37.8044, -122.2712))

    // --- UberProvider ---

    @Test
    fun uberProviderSignedOutThenSignedIn() = runTest {
        val provider = UberProvider(context)
        assertEquals(Provider.UBER, provider.provider)
        assertFalse(provider.isSignedIn())
        assertEquals(QuoteResult.NotSignedIn, provider.quotes(pickup, dropoff))
        UberSession(context).setSignedIn(true)
        assertTrue(provider.isSignedIn())
        val quotes = provider.quotes(pickup, dropoff)
        assertTrue(quotes is QuoteResult.Failed)
        assertTrue((quotes as QuoteResult.Failed).message.contains("captured yet"))
        // Interface defaults remain unsupported for Uber.
        assertTrue(provider.paymentMethods() is com.vayunmathur.taxi.data.PaymentMethodsResult.Unsupported)
        assertNull(provider.driverLocation("r1"))
        assertTrue(provider.cancelRide("r1") is com.vayunmathur.taxi.data.CancelResult.Unsupported)
        // Deep links.
        assertTrue(provider.bookingUri(pickup, dropoff, null).startsWith("https://m.uber.com/ul/"))
        // Session flag handling.
        assertTrue(UberSession.looksAuthenticated("https://m.uber.com/go/home"))
        assertFalse(UberSession.looksAuthenticated("https://auth.uber.com/login"))
        assertFalse(UberSession.looksAuthenticated("https://example.com/"))
        UberSession(context).clear()
        assertFalse(provider.isSignedIn())
    }

    // --- RemoteImageFetchers ---

    @Test
    fun remoteImageFetchersGateAndFetch() = runTest {
        http.on("images.example") { http.raw(200, "PNG BYTES", "image/png", it.url) }
        assertNull(RemoteImageFetchers.network(null, context))
        assertNull(RemoteImageFetchers.network(42, context))
        assertNull(RemoteImageFetchers.network(android.net.Uri.parse("file:///tmp/x.png"), context))
        assertNull(RemoteImageFetchers.network("file:///tmp/x.png", context))
        assertNull(RemoteImageFetchers.network("ftp://images.example/x.png", context))
        val fromString = RemoteImageFetchers.network("https://images.example/a.png", context)
        assertTrue(fromString is com.vayunmathur.library.image.fetchers.FetchResult.Bytes)
        assertEquals(
            "PNG BYTES",
            (fromString as com.vayunmathur.library.image.fetchers.FetchResult.Bytes)
                .bytes.toString(Charsets.UTF_8),
        )
        val fromUri = RemoteImageFetchers.network(
            android.net.Uri.parse("https://images.example/b.png"), context,
        )
        assertNotNull(fromUri)
        // Non-2xx is an IOException for the pipeline.
        http.uninstall(); http.install()
        http.on("images.example") { http.raw(404, "nope", "text/plain", it.url) }
        assertFailsWith<java.io.IOException> {
            RemoteImageFetchers.network("https://images.example/missing.png", context)
        }
    }

    // --- TaxiHttp production delegation (loopback, no overrides) ---

    @Test
    fun taxiHttpDelegatesToNetworkClient() = runTest {
        http.uninstall()
        NetworkClient.init(context, TrustBundle.SYSTEM)
        val payload = "loopback-body"
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/") { exchange ->
            val requestBody = exchange.requestBody.readAllBytes()
            val response = (payload + ":" + requestBody.size).toByteArray()
            exchange.responseHeaders.set("X-Test", "yes")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.write(response)
            exchange.close()
        }
        httpServer.start()
        server = httpServer
        val base = "http://127.0.0.1:${httpServer.address.port}"
        val executed = TaxiHttp.execute("$base/path", method = "POST", body = "abc")
        assertEquals(200, executed.status)
        assertEquals("loopback-body:3", executed.text)
        assertEquals("yes", executed.header("X-Test"))
        val performed = TaxiHttp.performRequest("$base/other", method = "GET")
        assertEquals(200, performed.status)
        assertTrue(performed.isSuccess)
        assertTrue(performed.body.startsWith("loopback-body"))
    }

    // --- LocationProvider with a last-known fix ---

    @Test
    fun locationCurrentUsesLastKnownFix() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.7749
            longitude = -122.4194
        }
        shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        val here = LocationProvider.current(context)
        assertEquals(LatLng(37.7749, -122.4194), here)
        // describe() falls back gracefully when the geocoder has no answer.
        val place = LocationProvider.describe(context, here!!)
        assertEquals(here, place.location)
    }

    // --- Card tokenizer protobuf + failure paths ---

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

    private fun stringField(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return record(field, 2, varintBytes(bytes.size.toLong()) + bytes)
    }

    private fun messageField(field: Int, vararg parts: ByteArray): ByteArray {
        val bytes = parts.fold(byteArrayOf()) { a, p -> a + p }
        return record(field, 2, varintBytes(bytes.size.toLong()) + bytes)
    }

    @Test
    fun tokenizerProtoConfigAndFailurePaths() = runTest {
        val session = LyftApiSession(context)
        val tokenizer = LyftCardTokenizer(json, session.systemTrustFactory, session::authJsonHeaders)
        val card = NewCard("4111111111111111", 12, 2030, "123", "94101")

        // fetchConfig over a protobuf strategies response (SetupIntent at tag 22).
        val setupIntent = messageField(
            22, stringField(1, "pk_proto"), stringField(2, "sec"), stringField(3, "seti_9"),
            stringField(4, "2024-01-01"),
        )
        val strategiesBody = messageField(1, setupIntent)
        http.on("/v1/tokenization_strategies") {
            com.vayunmathur.library.network.RawResponse(
                200, "OK", strategiesBody,
                mapOf("Content-Type" to listOf("application/x-protobuf")), it.url,
            )
        }
        // Seed a token in the store is unnecessary: fetchConfig takes it directly.
        val config = tokenizer.fetchConfig("access-tok", card)
        assertNotNull(config)
        assertEquals("stripe_setup_intent", config.provider)
        assertEquals("pk_proto", config.key)
        assertEquals("seti_9", config.setupIntentId)
        // parseConfigProto direct: legacy stripe (10) and braintree (11).
        val legacy = messageField(1, messageField(10, stringField(1, "pk_legacy")))
        assertEquals("pk_legacy", tokenizer.parseConfigProto(legacy)?.key)
        val bt = messageField(1, messageField(11, stringField(1, "bt_key")))
        assertEquals("braintree", tokenizer.parseConfigProto(bt)?.provider)
        assertNull(tokenizer.parseConfigProto(byteArrayOf(0x66, 0x01, 0x02)))

        // fetchConfig: HTTP failure and transport failure both yield null.
        http.uninstall(); http.install()
        http.on("/v1/tokenization_strategies") { http.raw(500, "x", "text/plain", it.url) }
        assertNull(tokenizer.fetchConfig("access-tok", card))
        TaxiHttp.executeOverride = { _, _, _, _, _ -> throw RuntimeException("socket exploded") }
        assertNull(tokenizer.fetchConfig("access-tok", card))
        TaxiHttp.executeOverride = null
        http.install()

        // Stripe transport failure and Braintree GraphQL errors.
        TaxiHttp.executeOverride = { _, _, _, _, _ -> throw RuntimeException("tls exploded") }
        val stripeErr = tokenizer.tokenizeStripe("pk", card)
        assertTrue(stripeErr is TokenizeResult.Err)
        assertTrue((stripeErr as TokenizeResult.Err).message.contains("tls exploded"))
        val btErrResult = tokenizer.tokenizeBraintree("%%%", card)
        assertTrue(btErrResult is TokenizeResult.Err)
        TaxiHttp.executeOverride = null
        http.install()
        http.on("payments.braintree-api.com") {
            http.raw(200, """{"errors":[{"message":"Card declined by gateway"}]}""", url = it.url)
        }
        val btToken = java.util.Base64.getEncoder().encodeToString(
            """{"authorizationFingerprint":"fp"}""".toByteArray(),
        )
        val gqlErr = tokenizer.tokenizeBraintree(btToken, card)
        assertTrue(gqlErr is TokenizeResult.Err)
        assertTrue((gqlErr as TokenizeResult.Err).message.contains("declined"))
    }
}
