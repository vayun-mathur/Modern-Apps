package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The anonymous-auth dispenser client against a loopback dispenser:
 * credentials decode, the device-properties JSON body (with escaping),
 * every HTTP status maps to its AuthError, and ensureAuthData builds
 * a session from a loopback dispense.
 */
@RunWith(RobolectricTestRunner::class)
class AnonymousAuthLoopbackTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val lastBody = AtomicReference<String?>(null)
    private val repo = AnonymousAuthRepository()

    private fun props(): Properties = DeviceInfoProvider.auroraProfile(
        ApplicationProvider.getApplicationContext<Context>(),
    ).apply {
        setProperty("Custom\"Key", "va\\l\nue")
    }

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/auth") { exchange ->
            lastBody.set(exchange.requestBody.readAllBytes().decodeToString())
            val bytes = """{"email": "anon@example.org", "authToken": "tok-123"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/garbage") { exchange ->
            val bytes = "not json".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        for ((path, code) in listOf(
            "/bad" to 400, "/vpn" to 403, "/missing" to 404,
            "/rate" to 429, "/maint" to 503, "/oops" to 500,
        )) {
            server.createContext(path) { exchange ->
                exchange.sendResponseHeaders(code, -1)
                exchange.close()
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    @Test
    fun `credentials decode and the request body carries the device properties`() = runBlocking {
        val result = repo.fetchAnonCredentials(props(), "$base/auth")
        val cred = result.getOrThrow()
        assertEquals("anon@example.org", cred.email)
        assertEquals("tok-123", cred.auth)
        val body = lastBody.get()
        assertTrue(body != null && body.contains("Build.BRAND"), "body was $body")
        // Quotes, backslashes and newlines in values are JSON-escaped.
        assertTrue(body.contains("\\\""), "unescaped quote in $body")
        assertTrue(body.contains("\\\\"), "unescaped backslash in $body")
    }

    @Test
    fun `each http status maps to its auth error`() = runBlocking {
        assertIs<AnonymousAuthRepository.AuthError.BadRequest>(
            repo.fetchAnonCredentials(props(), "$base/bad").exceptionOrNull(),
        )
        assertIs<AnonymousAuthRepository.AuthError.VpnRequired>(
            repo.fetchAnonCredentials(props(), "$base/vpn").exceptionOrNull(),
        )
        assertIs<AnonymousAuthRepository.AuthError.NotFound>(
            repo.fetchAnonCredentials(props(), "$base/missing").exceptionOrNull(),
        )
        assertIs<AnonymousAuthRepository.AuthError.RateLimited>(
            repo.fetchAnonCredentials(props(), "$base/rate").exceptionOrNull(),
        )
        assertIs<AnonymousAuthRepository.AuthError.Maintenance>(
            repo.fetchAnonCredentials(props(), "$base/maint").exceptionOrNull(),
        )
        val unknown = repo.fetchAnonCredentials(props(), "$base/oops").exceptionOrNull()
        assertIs<AnonymousAuthRepository.AuthError.Unknown>(unknown)
        assertEquals(500, unknown.code)
    }

    @Test
    fun `an undecodable success body is an unknown error`() = runBlocking {
        val error = repo.fetchAnonCredentials(props(), "$base/garbage").exceptionOrNull()
        assertIs<AnonymousAuthRepository.AuthError.Unknown>(error)
        assertEquals(200, error.code)
    }

    @Test
    fun `ensureAuthData cannot finish without the google checkin`() = runBlocking {
        // The dispenser half works over loopback, but AuthHelper.build
        // then performs a device checkin against Google, which is
        // unreachable here and surfaces as an IOException.
        assertFailsWith<java.io.IOException> {
            repo.ensureAuthData(props(), listOf("$base/auth"))
        }
        Unit
    }

    @Test
    fun `ensureAuthData with a permanently refusing dispenser fails fast with its error`() {
        runBlocking {
            val result = repo.ensureAuthData(props(), listOf("$base/bad"))
            assertIs<AnonymousAuthRepository.AuthError.BadRequest>(result.exceptionOrNull())
            Unit
        }
    }

    @Test
    fun `buildAuthData needs the google checkin`() = runBlocking {
        assertFailsWith<java.io.IOException> {
            repo.buildAuthData("anon@example.org", "tok-123", props())
        }
        Unit
    }

    @Test
    fun `error messages read for each error kind`() {
        assertTrue(repo.errorMessage(AnonymousAuthRepository.AuthError.RateLimited).contains("rate"))
        assertTrue(repo.errorMessage(AnonymousAuthRepository.AuthError.VpnRequired).contains("VPN"))
        assertEquals("Bad request to dispenser", repo.errorMessage(AnonymousAuthRepository.AuthError.BadRequest))
        assertEquals("Dispenser unreachable", repo.errorMessage(AnonymousAuthRepository.AuthError.NotFound))
        assertTrue(repo.errorMessage(AnonymousAuthRepository.AuthError.Maintenance).contains("maintenance"))
        assertEquals(
            "Dispenser error 500",
            repo.errorMessage(AnonymousAuthRepository.AuthError.Unknown(500, null)),
        )
        assertTrue(
            repo.errorMessage(AnonymousAuthRepository.AuthError.Network("boom"))
                .contains("Network error"),
        )
        assertEquals("weird", repo.errorMessage(IllegalStateException("weird")))
    }
}
