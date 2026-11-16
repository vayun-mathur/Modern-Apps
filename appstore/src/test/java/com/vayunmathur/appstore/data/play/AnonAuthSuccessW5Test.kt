package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.gplayapi.data.models.AuthData
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** W5: ensureAuthData success and creds-unusable retry, with a fake checkin builder. */
@RunWith(RobolectricTestRunner::class)
class AnonAuthSuccessW5Test {
    private lateinit var server: HttpServer
    private lateinit var base: String

    private fun props(): Properties = DeviceInfoProvider.auroraProfile(
        ApplicationProvider.getApplicationContext<Context>(),
    )

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/auth") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = """{"email": "anon@example.org", "authToken": "tok-123"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/rate") { exchange ->
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private fun authData(email: String): AuthData {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AuthData(
            email = email, aasToken = "aas", authToken = "auth-token",
            isAnonymous = true,
            deviceInfoProvider = com.aurora.gplayapi.data.providers.DeviceInfoProvider(
                DeviceInfoProvider.auroraProfile(context), "en_US",
            ),
        )
    }

    @Test
    fun ensureAuthDataSucceedsWithFakeCheckin() = runBlocking {
        val repo = AnonymousAuthRepository { email, _, _ -> authData(email) }
        val result = repo.ensureAuthData(props(), listOf("$base/auth"))
        assertEquals("anon@example.org", result.getOrThrow().email)
    }

    @Test
    fun ensureAuthDataRetriesWhenCredsAreUnusableThenSucceeds() = runBlocking {
        val attempts = AtomicInteger(0)
        val repo = AnonymousAuthRepository { email, _, _ ->
            if (attempts.incrementAndGet() < 2) throw IllegalStateException("bad creds")
            authData(email)
        }
        val result = repo.ensureAuthData(props(), listOf("$base/auth"))
        assertEquals("anon@example.org", result.getOrThrow().email)
        assertEquals(2, attempts.get())
    }

    @Test
    fun ensureAuthDataFallsThroughToSecondDispenser() = runBlocking {
        val repo = AnonymousAuthRepository { email, _, _ -> authData(email) }
        // First dispenser is permanently bad (400 via a missing path is 404 -
        // also non-transient), second serves credentials.
        val result = repo.ensureAuthData(props(), listOf("$base/missing", "$base/auth"))
        assertEquals("anon@example.org", result.getOrThrow().email)
        assertTrue(repo.errorMessage(AnonymousAuthRepository.AuthError.RateLimited).contains("rate"))
    }
}
